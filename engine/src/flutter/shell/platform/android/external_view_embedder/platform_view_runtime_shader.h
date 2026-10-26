// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#ifndef FLUTTER_SHELL_PLATFORM_ANDROID_EXTERNAL_VIEW_EMBEDDER_PLATFORM_VIEW_RUNTIME_SHADER_H_
#define FLUTTER_SHELL_PLATFORM_ANDROID_EXTERNAL_VIEW_EMBEDDER_PLATFORM_VIEW_RUNTIME_SHADER_H_

#include <cstdint>
#include <memory>
#include <mutex>
#include <optional>
#include <string>
#include <string_view>
#include <unordered_map>
#include <vector>

#include "flutter/display_list/effects/dl_runtime_effect.h"
#include "flutter/display_list/geometry/dl_geometry_types.h"
#include "flutter/fml/macros.h"
#include "flutter/impeller/core/runtime_types.h"

namespace impeller {
class RuntimeStage;
}  // namespace impeller

namespace flutter {

//------------------------------------------------------------------------------
/// @brief      A runtime effect image filter (|ui.ImageFilter.shader|),
///             translated so that it can be applied to platform views as an
///             Android |RuntimeShader| (AGSL) |RenderEffect|.
///
///             The program is derived from the SkSL runtime stage that
///             impellerc bundles next to the Impeller stages of a fragment
///             program (|DlRuntimeEffect::sksl_runtime_stage|). SkSL runtime
///             effects and AGSL are the same language (modulo the Skia version
///             of the device), so the SkSL source only needs a small amount
///             of adaptation:
///
///             - impellerc declares an extra `uniform half2 <sampler>_size`
///               for every sampler and samples with
///               `<sampler>.eval(<sampler>_size * uv)`. `half` can't represent
///               every pixel coordinate above 2048 exactly, so it is declared
///               as `float2` instead.
///
///             The float uniform data of a |DlRuntimeEffectImageFilter| is laid
///             out according to the Impeller runtime stage of the current
///             backend (exactly as the Dart |FragmentShader| API lays it out),
///             which can differ from the SkSL stage (e.g. on Vulkan all
///             uniforms are members of a single struct). Uniforms are therefore
///             matched by name.
///
///             Like Impeller (see |RuntimeEffectFilterContents|), the first
///             vec2 of the uniform data is overwritten with the size of the
///             filter input, and the input sampler's size uniform is set to
///             the same size.
///
///             Only filters with exactly one sampler (the filter input) are
///             supported.
///
class PlatformViewRuntimeShaderProgram {
 public:
  /// Describes how to compute the value of a single AGSL uniform.
  struct Uniform {
    enum class Source {
      /// The value is read from the filter's float uniform data.
      kUniformData,
      /// The value is the size of the filter input (two floats).
      kInputSize,
    };

    std::string name;
    Source source = Source::kUniformData;
    /// The offset (in floats) into the filter's float uniform data. Only
    /// meaningful for |Source::kUniformData|.
    size_t data_offset = 0;
    /// The number of floats of this uniform.
    size_t float_count = 0;
  };

  //----------------------------------------------------------------------------
  /// @brief      Creates a program from the SkSL runtime stage of a runtime
  ///             effect and the runtime stage whose layout the effect's
  ///             uniform data uses.
  ///
  /// @return     The program, or nullptr if the effect can't be represented
  ///             as an AGSL program, in which case |error| (if non-null)
  ///             describes why.
  ///
  static std::shared_ptr<PlatformViewRuntimeShaderProgram> Make(
      const impeller::RuntimeStage& sksl_stage,
      const impeller::RuntimeStage& data_layout_stage,
      std::string* error = nullptr);

  //----------------------------------------------------------------------------
  /// @brief      The same as |Make| above, but takes the SkSL source and the
  ///             uniform reflection of both stages directly.
  ///
  static std::shared_ptr<PlatformViewRuntimeShaderProgram> Make(
      std::string_view sksl_source,
      const std::vector<impeller::RuntimeUniformDescription>& sksl_uniforms,
      const std::vector<impeller::RuntimeUniformDescription>&
          data_layout_uniforms,
      std::string* error = nullptr);

  ~PlatformViewRuntimeShaderProgram();

  /// A process-wide unique identifier for this program.
  int64_t id() const { return id_; }

  /// The AGSL source of the program.
  const std::string& agsl_source() const { return agsl_source_; }

  /// The name of the `uniform shader` that receives the filter input, i.e.
  /// the name to pass to |RenderEffect.createRuntimeShaderEffect|.
  const std::string& input_shader_name() const { return input_shader_name_; }

  /// The float uniforms that need to be set for every frame, in the order in
  /// which |ComputeUniformValues| returns their values.
  const std::vector<Uniform>& uniforms() const { return uniforms_; }

  /// The number of floats returned by |ComputeUniformValues|.
  size_t total_float_count() const { return total_float_count_; }

  //----------------------------------------------------------------------------
  /// @brief      Computes the values of all |uniforms()|, concatenated in
  ///             order.
  ///
  /// @param[in]  uniform_data  The float uniform data of the filter.
  /// @param[in]  input_size    The size of the filter input in pixels.
  ///
  /// @return     The values, or std::nullopt if |uniform_data| is too small.
  ///
  std::optional<std::vector<float>> ComputeUniformValues(
      const std::vector<uint8_t>& uniform_data,
      DlISize input_size) const;

  /// Returns |sksl_source| with the adaptations described in the class
  /// documentation applied. Exposed for testing.
  static std::string AdaptSkSLToAGSL(
      std::string_view sksl_source,
      const std::vector<std::string>& sampler_names);

 private:
  PlatformViewRuntimeShaderProgram(int64_t id,
                                   std::string agsl_source,
                                   std::string input_shader_name,
                                   std::vector<Uniform> uniforms);

  const int64_t id_;
  const std::string agsl_source_;
  const std::string input_shader_name_;
  const std::vector<Uniform> uniforms_;
  size_t total_float_count_ = 0;

  FML_DISALLOW_COPY_AND_ASSIGN(PlatformViewRuntimeShaderProgram);
};

//------------------------------------------------------------------------------
/// @brief      A thread-safe cache of |PlatformViewRuntimeShaderProgram|s,
///             keyed by the SkSL runtime stage that they were created from, so
///             that every runtime effect is only translated (and sent to the
///             platform) once.
///
///             Failures are cached too, so that they are only logged once per
///             runtime effect.
///
class PlatformViewRuntimeShaderProgramCache {
 public:
  PlatformViewRuntimeShaderProgramCache();

  ~PlatformViewRuntimeShaderProgramCache();

  struct Result {
    /// The program, or nullptr if the effect is not supported.
    std::shared_ptr<PlatformViewRuntimeShaderProgram> program;
    /// Whether this is the first time that this program is returned by the
    /// cache, i.e. whether it still needs to be registered with the platform.
    bool is_new = false;
  };

  //----------------------------------------------------------------------------
  /// @brief      Returns the program for the given runtime effect, creating
  ///             it if needed.
  ///
  Result GetProgram(const DlRuntimeEffect& runtime_effect);

  /// The number of live entries. Exposed for testing.
  size_t GetEntryCountForTesting();

 private:
  struct Entry {
    // Used to detect that the stage was destroyed and its address reused.
    std::weak_ptr<impeller::RuntimeStage> stage;
    std::shared_ptr<PlatformViewRuntimeShaderProgram> program;
  };

  void PurgeExpiredEntries();

  std::mutex mutex_;
  std::unordered_map<const impeller::RuntimeStage*, Entry> entries_;

  FML_DISALLOW_COPY_AND_ASSIGN(PlatformViewRuntimeShaderProgramCache);
};

}  // namespace flutter

#endif  // FLUTTER_SHELL_PLATFORM_ANDROID_EXTERNAL_VIEW_EMBEDDER_PLATFORM_VIEW_RUNTIME_SHADER_H_
