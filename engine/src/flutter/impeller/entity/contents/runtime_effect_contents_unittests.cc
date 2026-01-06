// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#include "impeller/entity/contents/runtime_effect_contents.h"

#include <memory>

#include "gtest/gtest.h"
#include "impeller/core/runtime_types.h"
#include "impeller/core/shader_types.h"

namespace impeller {
namespace testing {

TEST(RuntimeEffectContentsTest, MakeShaderMetadataCalculatesByteLengthCorrectly) {
  // Case 1: Single float uniform.
  {
    RuntimeUniformDescription uniform;
    uniform.name = "uTime";
    uniform.type = kFloat;
    uniform.dimensions = {.rows = 1, .cols = 1};
    uniform.bit_width = 32;
    uniform.array_elements = std::nullopt;

    auto metadata = RuntimeEffectContents::MakeShaderMetadata(uniform);
    ASSERT_EQ(metadata->members.size(), 1u);
    EXPECT_EQ(metadata->members[0].type, ShaderType::kFloat);
    EXPECT_EQ(metadata->members[0].size, 4u);
    EXPECT_EQ(metadata->members[0].byte_length, 4u);
  }

  // Case 2: vec4 uniform.
  {
    RuntimeUniformDescription uniform;
    uniform.name = "uColor";
    uniform.type = kFloat;
    uniform.dimensions = {.rows = 4, .cols = 1};
    uniform.bit_width = 32;
    uniform.array_elements = std::nullopt;

    auto metadata = RuntimeEffectContents::MakeShaderMetadata(uniform);
    ASSERT_EQ(metadata->members.size(), 1u);
    EXPECT_EQ(metadata->members[0].type, ShaderType::kFloat);
    EXPECT_EQ(metadata->members[0].size, 16u);
    EXPECT_EQ(metadata->members[0].byte_length, 16u);
  }

  // Case 3: float array uniform (float values[4]).
  {
    RuntimeUniformDescription uniform;
    uniform.name = "uFloats";
    uniform.type = kFloat;
    uniform.dimensions = {.rows = 1, .cols = 1};
    uniform.bit_width = 32;
    uniform.array_elements = 4;

    auto metadata = RuntimeEffectContents::MakeShaderMetadata(uniform);
    ASSERT_EQ(metadata->members.size(), 1u);
    EXPECT_EQ(metadata->members[0].type, ShaderType::kFloat);
    EXPECT_EQ(metadata->members[0].size, 4u); // Size of single element type usually
    // However, looking at logic: size = rows * cols * (bit_width/8)
    // So size is 4.
    EXPECT_EQ(metadata->members[0].size, 4u);
    // Byte length = size * array_elements = 4 * 4 = 16.
    EXPECT_EQ(metadata->members[0].byte_length, 16u);
  }

  // Case 4: vec4 array uniform (vec4 values[4]) - The bug fix target.
  {
    RuntimeUniformDescription uniform;
    uniform.name = "uVecs";
    uniform.type = kFloat;
    uniform.dimensions = {.rows = 4, .cols = 1};
    uniform.bit_width = 32;
    uniform.array_elements = 4;

    auto metadata = RuntimeEffectContents::MakeShaderMetadata(uniform);
    ASSERT_EQ(metadata->members.size(), 1u);
    EXPECT_EQ(metadata->members[0].type, ShaderType::kFloat);
    EXPECT_EQ(metadata->members[0].size, 16u); // 4 floats * 4 bytes
    // Byte length SHOULD include array elements AND vector size.
    // 16 bytes per element * 4 elements = 64 bytes.
    EXPECT_EQ(metadata->members[0].byte_length, 64u);
  }
}

}  // namespace testing
}  // namespace impeller
