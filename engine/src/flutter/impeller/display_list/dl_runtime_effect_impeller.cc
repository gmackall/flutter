// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#include "flutter/impeller/display_list/dl_runtime_effect_impeller.h"
#include "flutter/impeller/runtime_stage/runtime_stage.h"
#include "third_party/skia/include/effects/SkRuntimeEffect.h"

namespace flutter {

//------------------------------------------------------------------------------
/// DlRuntimeEffectImpeller
///

sk_sp<DlRuntimeEffect> DlRuntimeEffectImpeller::Make(
    std::shared_ptr<impeller::RuntimeStage> runtime_stage,
    sk_sp<SkRuntimeEffect> skia_effect) {
  if (runtime_stage) {
     FML_LOG(ERROR) << "HI GRAY, RuntimeStage Uniforms:";
     for (const auto& u : runtime_stage->GetUniforms()) {
         FML_LOG(ERROR) << "  Name: " << u.name << " Size: " << u.GetSize() << " Type: " << (int)u.type;
     }
  }
  if (skia_effect) {
     FML_LOG(ERROR) << "HI GRAY, SkRuntimeEffect Uniforms:";
     for (const auto& u : skia_effect->uniforms()) {
         FML_LOG(ERROR) << "  Name: " << u.name << " Offset: " << u.offset << " Size: " << u.sizeInBytes();
     }
  }
  return sk_make_sp<DlRuntimeEffectImpeller>(std::move(runtime_stage),
                                             std::move(skia_effect));
}

DlRuntimeEffectImpeller::~DlRuntimeEffectImpeller() = default;

DlRuntimeEffectImpeller::DlRuntimeEffectImpeller(
    std::shared_ptr<impeller::RuntimeStage> runtime_stage,
    sk_sp<SkRuntimeEffect> skia_effect)
    : runtime_stage_(std::move(runtime_stage)),
      skia_effect_(std::move(skia_effect)) {};

sk_sp<SkRuntimeEffect> DlRuntimeEffectImpeller::skia_runtime_effect() const {
  return skia_effect_;
}

std::shared_ptr<impeller::RuntimeStage> DlRuntimeEffectImpeller::runtime_stage()
    const {
  return runtime_stage_;
}

size_t DlRuntimeEffectImpeller::uniform_size() const {
  if (!runtime_stage_) {
    return 0;
  }

  size_t total = 0;
  for (const auto& uniform : runtime_stage_->GetUniforms()) {
    total += uniform.GetSize();
  }
  return total;
}

std::shared_ptr<std::vector<uint8_t>> DlRuntimeEffectImpeller::GetSkiaUniformData(
    const std::shared_ptr<std::vector<uint8_t>>& impeller_data) const {
  if (!impeller_data || !skia_effect_ || !runtime_stage_) {
    return impeller_data;
  }

  auto skia_data = std::make_shared<std::vector<uint8_t>>(
      skia_effect_->uniformSize());

  // Create a map of Impeller uniform names to their descriptions
  std::map<std::string, impeller::RuntimeUniformDescription> impeller_uniforms;
  FML_LOG(ERROR) << "HI GRAY, Building Impeller Map:";
  for (const auto& u : runtime_stage_->GetUniforms()) {
    impeller_uniforms[u.name] = u;
    FML_LOG(ERROR) << "  Inserted: '" << u.name << "' (len: " << u.name.length() << ")";
  }

  // Iterate over Skia uniforms and find matching Impeller uniform data
  const std::string kReservedBlockName = "_RESERVED_IDENTIFIER_FIXUP_gl_DefaultUniformBlock";
  bool has_reserved_block = impeller_uniforms.find(kReservedBlockName) != impeller_uniforms.end();
  size_t reserved_block_offset = 0;

  if (has_reserved_block) {
     for (const auto& u : runtime_stage_->GetUniforms()) {
         if (u.name == kReservedBlockName) {
             break;
         }
         reserved_block_offset += u.GetSize();
     }
     FML_LOG(ERROR) << "HI GRAY, Found reserved block at offset: " << reserved_block_offset;
  }

  for (const auto& sk_uniform : skia_effect_->uniforms()) {
    std::string sk_name(sk_uniform.name);
    auto it = impeller_uniforms.find(sk_name);

    // Special handling for u_texture_size which might be missing from Impeller reflection
    // but is required by Skia (and should match u_size).
    if (sk_name == "u_texture_size") {
         FML_LOG(ERROR) << "HI GRAY, Injecting u_texture_size from u_size";
         // We assume u_size is at offset 0 of the data if we are using the reserved block,
         // OR we can look up u_size in the map?
         // But u_layout generally matches the reserved block layout for these fields.
         // Let's assume u_size is the first 8 bytes of the data if the reserved block is at 0.
         // The reserved block is usually the first thing for these shaders.
         // Let's confirm if we found a reserved block.
         auto block_it = impeller_uniforms.find("_RESERVED_IDENTIFIER_FIXUP_gl_DefaultUniformBlock");
         if (block_it != impeller_uniforms.end()) {
             const auto& block_uniform = block_it->second;
             // u_size should be at the start of this block.
             // We need to calculate the actual offset of the reserved block in the impeller_data.
             size_t current_reserved_block_offset = 0;
             for (const auto& u : runtime_stage_->GetUniforms()) {
                 if (u.name == kReservedBlockName) {
                     break;
                 }
                 current_reserved_block_offset += u.GetSize();
             }

             if (block_uniform.GetSize() >= 8) {
                 const uint8_t* val = impeller_data->data() + current_reserved_block_offset; // u_size is at start of block
                 uint8_t* dst = skia_data->data() + sk_uniform.offset;
                 memcpy(dst, val, 8); // Copy 8 bytes (vec2)
                 FML_LOG(ERROR) << "HI GRAY, Injected u_texture_size: " << *(float*)val << ", " << *(float*)(val+4);
             }
         }
         continue;
    }

    // Fallback to reserved block if specific uniform not found
    if (it == impeller_uniforms.end() && has_reserved_block) {
        FML_LOG(ERROR) << "HI GRAY, Using reserved block for: " << sk_name;
        // Assume the block contains the uniforms in Skia's order/layout
        // We read from impeller_data at (reserved_block_offset + sk_uniform.offset)
        size_t source_offset = reserved_block_offset + sk_uniform.offset;
        size_t copy_size = sk_uniform.sizeInBytes();

        if (source_offset + copy_size <= impeller_data->size() &&
            sk_uniform.offset + copy_size <= skia_data->size()) {
            memcpy(skia_data->data() + sk_uniform.offset,
                   impeller_data->data() + source_offset,
                   copy_size);
        } else {
             FML_LOG(ERROR) << "HI GRAY, Bounds check failed for " << sk_name
                            << " src: " << source_offset << " len: " << copy_size
                            << " max: " << impeller_data->size();
        }
        continue;
    }

    if (it != impeller_uniforms.end()) {
      const auto& imp_uniform = it->second;

      // Check bounds
      // Wait, Impeller RuntimeStage::GetUniforms() returns descriptions.
      // The data block from Dart is packed according to these descriptions.
      // We need to calculate the offset in the *packed data buffer* (impeller_data).
      // FragmentProgram packs them in order of GetUniforms().

      // Let's re-calculate offsets in the packed buffer.
      size_t packed_offset = 0;
      for (const auto& u : runtime_stage_->GetUniforms()) {
        if (u.name == sk_uniform.name) {
             break;
        }
        packed_offset += u.GetSize();
      }

      size_t copy_size = std::min(sk_uniform.sizeInBytes(), imp_uniform.GetSize());

      FML_LOG(ERROR) << "HI GRAY, Checking Uniform: " << sk_uniform.name
                     << " sk_offset: " << sk_uniform.offset
                     << " imp_offset: " << packed_offset
                     << " copy_size: " << copy_size
                     << " imp_data_size: " << impeller_data->size()
                     << " sk_data_size: " << skia_data->size();

      if (packed_offset + copy_size <= impeller_data->size() &&
          sk_uniform.offset + copy_size <= skia_data->size()) {
        memcpy(skia_data->data() + sk_uniform.offset,
               impeller_data->data() + packed_offset,
               copy_size);
      } else {
        FML_LOG(ERROR) << "HI GRAY, Bounds check failed for " << sk_uniform.name;
      }
    } else {
        FML_LOG(ERROR) << "HI GRAY, Impeller uniform not found for " << sk_uniform.name;
    }
  }

  return skia_data;
}

}  // namespace flutter

