// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#include "flutter/shell/platform/android/external_view_embedder/platform_view_runtime_shader.h"

#include <atomic>
#include <cstring>
#include <sstream>

#include "flutter/fml/logging.h"
#include "flutter/impeller/runtime_stage/runtime_stage.h"

namespace flutter {

namespace {

std::atomic<int64_t> g_next_program_id = 1;

void SetError(std::string* error, const std::string& message) {
  if (error) {
    *error = message;
  }
}

struct DataSlot {
  size_t offset = 0;
  size_t float_count = 0;
};

// Mirrors |FragmentProgram._getUniformFloatInfo| in lib/ui/painting.dart,
// which defines the layout of the float uniform data of a fragment shader.
std::unordered_map<std::string, DataSlot> ComputeDataLayout(
    const std::vector<impeller::RuntimeUniformDescription>& uniforms) {
  std::unordered_map<std::string, DataSlot> layout;
  size_t offset = 0;
  for (const impeller::RuntimeUniformDescription& uniform : uniforms) {
    switch (uniform.type) {
      case impeller::RuntimeUniformType::kSampledImage:
        break;
      case impeller::RuntimeUniformType::kStruct:
        for (const impeller::StructField& field : uniform.struct_fields) {
          const size_t float_count = field.byte_size / sizeof(float);
          layout.emplace(field.name, DataSlot{offset, float_count});
          offset += float_count;
        }
        break;
      case impeller::RuntimeUniformType::kFloat: {
        const size_t float_count = uniform.GetDartSize() / sizeof(float);
        layout.emplace(uniform.name, DataSlot{offset, float_count});
        offset += float_count;
        break;
      }
    }
  }
  return layout;
}

}  // namespace

std::string PlatformViewRuntimeShaderProgram::AdaptSkSLToAGSL(
    std::string_view sksl_source,
    const std::vector<std::string>& sampler_names) {
  std::string agsl(sksl_source);
  for (const std::string& sampler_name : sampler_names) {
    // See |CompilerSkSL::emit_uniform| in impeller/compiler/spirv_sksl.cc.
    const std::string half_declaration =
        "uniform half2 " + sampler_name + "_size;";
    const std::string float_declaration =
        "uniform float2 " + sampler_name + "_size;";
    size_t pos = agsl.find(half_declaration);
    if (pos != std::string::npos) {
      agsl.replace(pos, half_declaration.size(), float_declaration);
    }
  }
  return agsl;
}

std::shared_ptr<PlatformViewRuntimeShaderProgram>
PlatformViewRuntimeShaderProgram::Make(
    const impeller::RuntimeStage& sksl_stage,
    const impeller::RuntimeStage& data_layout_stage,
    std::string* error) {
  const std::shared_ptr<fml::Mapping>& code = sksl_stage.GetCodeMapping();
  if (!code || code->GetMapping() == nullptr || code->GetSize() == 0) {
    SetError(error, "The SkSL runtime stage has no code.");
    return nullptr;
  }
  // The SkSL code may or may not be null terminated.
  std::string_view source(reinterpret_cast<const char*>(code->GetMapping()),
                          code->GetSize());
  size_t terminator = source.find('\0');
  if (terminator != std::string_view::npos) {
    source = source.substr(0, terminator);
  }
  return Make(source, sksl_stage.GetUniforms(), data_layout_stage.GetUniforms(),
              error);
}

std::shared_ptr<PlatformViewRuntimeShaderProgram>
PlatformViewRuntimeShaderProgram::Make(
    std::string_view sksl_source,
    const std::vector<impeller::RuntimeUniformDescription>& sksl_uniforms,
    const std::vector<impeller::RuntimeUniformDescription>&
        data_layout_uniforms,
    std::string* error) {
  std::vector<std::string> sampler_names;
  for (const impeller::RuntimeUniformDescription& uniform : sksl_uniforms) {
    if (uniform.type == impeller::RuntimeUniformType::kSampledImage) {
      sampler_names.push_back(uniform.name);
    }
  }
  if (sampler_names.size() != 1u) {
    std::stringstream stream;
    stream << "Only shaders with exactly one sampler (the filter input) are "
              "supported, but the shader has "
           << sampler_names.size() << ".";
    SetError(error, stream.str());
    return nullptr;
  }

  const std::unordered_map<std::string, DataSlot> layout =
      ComputeDataLayout(data_layout_uniforms);

  std::vector<Uniform> uniforms;
  for (const impeller::RuntimeUniformDescription& uniform : sksl_uniforms) {
    switch (uniform.type) {
      case impeller::RuntimeUniformType::kSampledImage:
        continue;
      case impeller::RuntimeUniformType::kStruct:
        SetError(error,
                 "Struct uniforms are not supported: '" + uniform.name + "'.");
        return nullptr;
      case impeller::RuntimeUniformType::kFloat:
        break;
    }
    if (uniform.bit_width != 32u) {
      SetError(error, "Only 32 bit float uniforms are supported: '" +
                          uniform.name + "'.");
      return nullptr;
    }
    const size_t float_count = uniform.GetDartSize() / sizeof(float);
    auto slot = layout.find(uniform.name);
    if (slot == layout.end()) {
      SetError(error, "Uniform '" + uniform.name +
                          "' was not found in the uniform data layout.");
      return nullptr;
    }
    if (slot->second.float_count != float_count) {
      std::stringstream stream;
      stream << "Uniform '" << uniform.name << "' has " << float_count
             << " floats in the SkSL stage but " << slot->second.float_count
             << " in the uniform data layout.";
      SetError(error, stream.str());
      return nullptr;
    }
    uniforms.push_back(Uniform{
        .name = uniform.name,
        .source = Uniform::Source::kUniformData,
        .data_offset = slot->second.offset,
        .float_count = float_count,
    });
  }

  const std::string& input_shader_name = sampler_names.front();
  const std::string input_size_name = input_shader_name + "_size";
  if (sksl_source.find(input_size_name) != std::string_view::npos) {
    uniforms.push_back(Uniform{
        .name = input_size_name,
        .source = Uniform::Source::kInputSize,
        .float_count = 2u,
    });
  }

  return std::shared_ptr<PlatformViewRuntimeShaderProgram>(
      new PlatformViewRuntimeShaderProgram(
          g_next_program_id.fetch_add(1),
          AdaptSkSLToAGSL(sksl_source, sampler_names), input_shader_name,
          std::move(uniforms)));
}

PlatformViewRuntimeShaderProgram::PlatformViewRuntimeShaderProgram(
    int64_t id,
    std::string agsl_source,
    std::string input_shader_name,
    std::vector<Uniform> uniforms)
    : id_(id),
      agsl_source_(std::move(agsl_source)),
      input_shader_name_(std::move(input_shader_name)),
      uniforms_(std::move(uniforms)) {
  for (const Uniform& uniform : uniforms_) {
    total_float_count_ += uniform.float_count;
  }
}

PlatformViewRuntimeShaderProgram::~PlatformViewRuntimeShaderProgram() = default;

std::optional<std::vector<float>>
PlatformViewRuntimeShaderProgram::ComputeUniformValues(
    const std::vector<uint8_t>& uniform_data,
    DlISize input_size) const {
  const size_t data_float_count = uniform_data.size() / sizeof(float);
  // Like |RuntimeEffectFilterContents|, require at least the vec2 size.
  if (data_float_count < 2u) {
    return std::nullopt;
  }
  std::vector<float> data(data_float_count);
  memcpy(data.data(), uniform_data.data(), data_float_count * sizeof(float));
  // Impeller overwrites the first vec2 of the uniform data with the size of
  // the input texture. See |RuntimeEffectFilterContents::RenderFilter|.
  const float width = static_cast<float>(input_size.width);
  const float height = static_cast<float>(input_size.height);
  data[0] = width;
  data[1] = height;

  std::vector<float> values;
  values.reserve(total_float_count_);
  for (const Uniform& uniform : uniforms_) {
    switch (uniform.source) {
      case Uniform::Source::kInputSize:
        values.push_back(width);
        values.push_back(height);
        break;
      case Uniform::Source::kUniformData:
        if (uniform.data_offset + uniform.float_count > data_float_count) {
          return std::nullopt;
        }
        values.insert(values.end(), data.begin() + uniform.data_offset,
                      data.begin() + uniform.data_offset + uniform.float_count);
        break;
    }
  }
  return values;
}

PlatformViewRuntimeShaderProgramCache::PlatformViewRuntimeShaderProgramCache() =
    default;

PlatformViewRuntimeShaderProgramCache::
    ~PlatformViewRuntimeShaderProgramCache() = default;

PlatformViewRuntimeShaderProgramCache::Result
PlatformViewRuntimeShaderProgramCache::GetProgram(
    const DlRuntimeEffect& runtime_effect) {
  std::shared_ptr<impeller::RuntimeStage> sksl_stage =
      runtime_effect.sksl_runtime_stage();
  std::shared_ptr<impeller::RuntimeStage> data_layout_stage =
      runtime_effect.runtime_stage();
  if (!sksl_stage || !data_layout_stage) {
    static std::once_flag log_once;
    std::call_once(log_once, [] {
      FML_LOG(WARNING)
          << "A platform view is drawn under an ImageFilter.shader whose "
             "shader has no SkSL variant (it was not compiled with --sksl). "
             "The filter will not be applied to platform views.";
    });
    return {};
  }

  std::scoped_lock lock(mutex_);
  auto it = entries_.find(sksl_stage.get());
  if (it != entries_.end() && !it->second.stage.expired()) {
    return {.program = it->second.program, .is_new = false};
  }

  PurgeExpiredEntries();

  std::string error;
  std::shared_ptr<PlatformViewRuntimeShaderProgram> program =
      PlatformViewRuntimeShaderProgram::Make(*sksl_stage, *data_layout_stage,
                                             &error);
  if (!program) {
    FML_LOG(WARNING) << "ImageFilter.shader can't be applied to platform "
                        "views: "
                     << error;
  }
  entries_.insert_or_assign(sksl_stage.get(),
                            Entry{.stage = sksl_stage, .program = program});
  return {.program = program, .is_new = program != nullptr};
}

size_t PlatformViewRuntimeShaderProgramCache::GetEntryCountForTesting() {
  std::scoped_lock lock(mutex_);
  PurgeExpiredEntries();
  return entries_.size();
}

void PlatformViewRuntimeShaderProgramCache::PurgeExpiredEntries() {
  for (auto it = entries_.begin(); it != entries_.end();) {
    if (it->second.stage.expired()) {
      it = entries_.erase(it);
    } else {
      ++it;
    }
  }
}

}  // namespace flutter
