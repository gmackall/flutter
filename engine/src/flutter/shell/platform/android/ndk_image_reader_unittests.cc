// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#include <android/native_window.h>
#include <unistd.h>

#include <atomic>
#include <cstdint>
#include <deque>
#include <memory>
#include <mutex>
#include <thread>
#include <unordered_map>
#include <vector>

#include "flutter/common/graphics/texture.h"
#include "flutter/display_list/dl_builder.h"
#include "flutter/display_list/dl_sampling_options.h"
#include "flutter/display_list/image/dl_image.h"
#include "flutter/fml/synchronization/waitable_event.h"
#include "flutter/fml/thread.h"
#include "flutter/impeller/toolkit/android/hardware_buffer.h"
#include "flutter/impeller/toolkit/android/proc_table.h"
#include "flutter/shell/platform/android/image_external_texture.h"
#include "flutter/shell/platform/android/image_external_texture_gl.h"
#include "flutter/shell/platform/android/image_external_texture_vk_impeller.h"
#include "flutter/shell/platform/android/ndk_image_reader.h"
#include "gtest/gtest.h"

namespace flutter::testing {
namespace {

class CountingDlImage final : public DlImage {
 public:
  explicit CountingDlImage(std::atomic<int>* live_count,
                           DlISize size = DlISize(64, 64))
      : live_count_(live_count), size_(size) {
    if (live_count_ != nullptr) {
      live_count_->fetch_add(1, std::memory_order_relaxed);
    }
  }

  ~CountingDlImage() override {
    if (live_count_ != nullptr) {
      live_count_->fetch_sub(1, std::memory_order_relaxed);
    }
  }

  Type GetImageType() const override { return Type::kImpeller; }
  bool isTextureBacked() const override { return true; }
  DlColorSpace GetColorSpace() const override { return DlColorSpace::kSRGB; }
  bool isOpaque() const override { return false; }
  bool isUIThreadSafe() const override { return true; }
  DlISize GetSize() const override { return size_; }
  size_t GetApproximateByteSize() const override { return sizeof(*this); }

 private:
  std::atomic<int>* live_count_;
  DlISize size_;
};

template <typename Func>
void RunSyncOnTaskRunner(const fml::RefPtr<fml::TaskRunner>& runner,
                         Func&& fn) {
  fml::AutoResetWaitableEvent done;
  fml::TaskRunner::RunNowOrPostTask(runner, [&]() {
    fn();
    done.Signal();
  });
  done.Wait();
}

struct FakeAhbEntry {
  uint64_t system_id = 0;
  AHardwareBuffer_Desc desc = {};
  int ref_count = 1;
};

struct FakeReaderEntry {
  int32_t width = 0;
  int32_t height = 0;
  int32_t format = 0;
  uint64_t usage = 0;
  int32_t max_images = 0;
  ANativeWindow* window = nullptr;
  AImageReader_ImageListener image_listener = {};
  bool has_image_listener = false;
  AImageReader_BufferRemovedListener buffer_removed_listener = {};
  bool has_buffer_removed_listener = false;
  std::deque<std::pair<AImage*, int>> pending_images;
};

class FakeNdkEnvironment {
 public:
  FakeNdkEnvironment() {
    auto& procs = impeller::android::GetMutableProcTable();
    saved_ahb_get_id_ = procs.AHardwareBuffer_getId.proc;
    saved_ahb_describe_ = procs.AHardwareBuffer_describe.proc;
    saved_ahb_acquire_ = procs.AHardwareBuffer_acquire.proc;
    saved_ahb_release_ = procs.AHardwareBuffer_release.proc;
    saved_reader_new_ = procs.AImageReader_newWithUsage.proc;
    saved_reader_delete_ = procs.AImageReader_delete.proc;
    saved_reader_get_window_ = procs.AImageReader_getWindow.proc;
    saved_reader_acquire_ = procs.AImageReader_acquireLatestImageAsync.proc;
    saved_reader_set_image_listener_ = procs.AImageReader_setImageListener.proc;
    saved_reader_set_removed_listener_ =
        procs.AImageReader_setBufferRemovedListener.proc;
    saved_image_delete_ = procs.AImage_delete.proc;
    saved_image_delete_async_ = procs.AImage_deleteAsync.proc;
    saved_image_get_ahb_ = procs.AImage_getHardwareBuffer.proc;

    g_active_ = this;

    procs.AHardwareBuffer_getId.proc = &FakeNdkEnvironment::AhbGetId;
    procs.AHardwareBuffer_describe.proc = &FakeNdkEnvironment::AhbDescribe;
    procs.AHardwareBuffer_acquire.proc = &FakeNdkEnvironment::AhbAcquire;
    procs.AHardwareBuffer_release.proc = &FakeNdkEnvironment::AhbRelease;
    procs.AImageReader_newWithUsage.proc = &FakeNdkEnvironment::ReaderNew;
    procs.AImageReader_delete.proc = &FakeNdkEnvironment::ReaderDelete;
    procs.AImageReader_getWindow.proc = &FakeNdkEnvironment::ReaderGetWindow;
    procs.AImageReader_acquireLatestImageAsync.proc =
        &FakeNdkEnvironment::ReaderAcquireAsync;
    procs.AImageReader_setImageListener.proc =
        &FakeNdkEnvironment::ReaderSetImageListener;
    procs.AImageReader_setBufferRemovedListener.proc =
        &FakeNdkEnvironment::ReaderSetBufferRemovedListener;
    procs.AImage_delete.proc = &FakeNdkEnvironment::ImageDelete;
    procs.AImage_deleteAsync.proc = &FakeNdkEnvironment::ImageDeleteAsync;
    procs.AImage_getHardwareBuffer.proc = &FakeNdkEnvironment::ImageGetAhb;
  }

  ~FakeNdkEnvironment() {
    auto& procs = impeller::android::GetMutableProcTable();
    procs.AHardwareBuffer_getId.proc = saved_ahb_get_id_;
    procs.AHardwareBuffer_describe.proc = saved_ahb_describe_;
    procs.AHardwareBuffer_acquire.proc = saved_ahb_acquire_;
    procs.AHardwareBuffer_release.proc = saved_ahb_release_;
    procs.AImageReader_newWithUsage.proc = saved_reader_new_;
    procs.AImageReader_delete.proc = saved_reader_delete_;
    procs.AImageReader_getWindow.proc = saved_reader_get_window_;
    procs.AImageReader_acquireLatestImageAsync.proc = saved_reader_acquire_;
    procs.AImageReader_setImageListener.proc = saved_reader_set_image_listener_;
    procs.AImageReader_setBufferRemovedListener.proc =
        saved_reader_set_removed_listener_;
    procs.AImage_delete.proc = saved_image_delete_;
    procs.AImage_deleteAsync.proc = saved_image_delete_async_;
    procs.AImage_getHardwareBuffer.proc = saved_image_get_ahb_;
    g_active_ = nullptr;
  }

  void SetApi31SystemIdEnabled(bool enabled) {
    impeller::android::GetMutableProcTable().AHardwareBuffer_getId.proc =
        enabled ? &FakeNdkEnvironment::AhbGetId : nullptr;
  }

  AHardwareBuffer* CreateFakeBuffer(uint64_t system_id,
                                    uint32_t width = 128,
                                    uint32_t height = 96) {
    std::lock_guard<std::mutex> lock(mutex_);
    auto* token = reinterpret_cast<AHardwareBuffer*>(next_ahb_addr_);
    next_ahb_addr_ += 64;
    AHardwareBuffer_Desc desc = {};
    desc.width = width;
    desc.height = height;
    desc.layers = 1;
    desc.format = AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM;
    desc.usage = AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE;
    ahbs_[token] = FakeAhbEntry{.system_id = system_id, .desc = desc};
    return token;
  }

  AImage* EnqueueFakeImage(AImageReader* reader,
                           AHardwareBuffer* ahb,
                           int fence_fd = -1) {
    std::lock_guard<std::mutex> lock(mutex_);
    auto* img = reinterpret_cast<AImage*>(next_image_addr_);
    next_image_addr_ += 64;
    images_[img] = ahb;
    readers_[reader].pending_images.emplace_back(img, fence_fd);
    return img;
  }

  void FireImageAvailable(AImageReader* reader) {
    AImageReader_ImageListener listener = {};
    bool valid = false;
    {
      std::lock_guard<std::mutex> lock(mutex_);
      auto it = readers_.find(reader);
      if (it != readers_.end() && it->second.has_image_listener) {
        listener = it->second.image_listener;
        valid = listener.onImageAvailable != nullptr;
      }
    }
    if (valid) {
      listener.onImageAvailable(listener.context, reader);
    }
  }

  void FireBufferRemoved(AImageReader* reader, AHardwareBuffer* ahb) {
    AImageReader_BufferRemovedListener listener = {};
    bool valid = false;
    {
      std::lock_guard<std::mutex> lock(mutex_);
      auto it = readers_.find(reader);
      if (it != readers_.end() && it->second.has_buffer_removed_listener) {
        listener = it->second.buffer_removed_listener;
        valid = listener.onBufferRemoved != nullptr;
      }
    }
    if (valid) {
      listener.onBufferRemoved(listener.context, reader, ahb);
    }
  }

  AImageReader* LastCreatedReader() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return created_readers_.empty() ? nullptr : created_readers_.back();
  }

  FakeReaderEntry GetReaderSnapshot(AImageReader* reader) const {
    std::lock_guard<std::mutex> lock(mutex_);
    auto it = readers_.find(reader);
    return it == readers_.end() ? FakeReaderEntry{} : it->second;
  }

  int DeletedImageCount() const {
    return deleted_images_.load(std::memory_order_acquire);
  }
  int DeletedReaderCount() const {
    return deleted_readers_.load(std::memory_order_acquire);
  }
  std::thread::id LastReaderDeleteThread() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return last_reader_delete_thread_;
  }

 private:
  static int AhbGetId(const AHardwareBuffer* ahb, uint64_t* out_id) {
    std::lock_guard<std::mutex> lock(g_active_->mutex_);
    auto it = g_active_->ahbs_.find(const_cast<AHardwareBuffer*>(ahb));
    if (it == g_active_->ahbs_.end()) {
      return -1;
    }
    *out_id = it->second.system_id;
    return 0;
  }

  static void AhbDescribe(const AHardwareBuffer* ahb,
                          AHardwareBuffer_Desc* out_desc) {
    std::lock_guard<std::mutex> lock(g_active_->mutex_);
    auto it = g_active_->ahbs_.find(const_cast<AHardwareBuffer*>(ahb));
    if (it != g_active_->ahbs_.end() && out_desc != nullptr) {
      *out_desc = it->second.desc;
    }
  }

  static void AhbAcquire(AHardwareBuffer* ahb) {
    std::lock_guard<std::mutex> lock(g_active_->mutex_);
    auto it = g_active_->ahbs_.find(ahb);
    if (it != g_active_->ahbs_.end()) {
      ++it->second.ref_count;
    }
  }

  static void AhbRelease(AHardwareBuffer* ahb) {
    std::lock_guard<std::mutex> lock(g_active_->mutex_);
    auto it = g_active_->ahbs_.find(ahb);
    if (it != g_active_->ahbs_.end()) {
      --it->second.ref_count;
    }
  }

  static media_status_t ReaderNew(int32_t width,
                                  int32_t height,
                                  int32_t format,
                                  uint64_t usage,
                                  int32_t max_images,
                                  AImageReader** out_reader) {
    std::lock_guard<std::mutex> lock(g_active_->mutex_);
    auto* r = reinterpret_cast<AImageReader*>(g_active_->next_reader_addr_);
    auto* w = reinterpret_cast<ANativeWindow*>(g_active_->next_window_addr_);
    g_active_->next_reader_addr_ += 64;
    g_active_->next_window_addr_ += 64;
    g_active_->readers_[r] = FakeReaderEntry{
        .width = width,
        .height = height,
        .format = format,
        .usage = usage,
        .max_images = max_images,
        .window = w,
    };
    g_active_->created_readers_.push_back(r);
    *out_reader = r;
    return AMEDIA_OK;
  }

  static void ReaderDelete(AImageReader* reader) {
    std::lock_guard<std::mutex> lock(g_active_->mutex_);
    auto it = g_active_->readers_.find(reader);
    if (it != g_active_->readers_.end()) {
      EXPECT_FALSE(it->second.has_image_listener);
      EXPECT_FALSE(it->second.has_buffer_removed_listener);
      g_active_->readers_.erase(it);
    }
    g_active_->last_reader_delete_thread_ = std::this_thread::get_id();
    g_active_->deleted_readers_.fetch_add(1, std::memory_order_release);
  }

  static media_status_t ReaderGetWindow(AImageReader* reader,
                                        ANativeWindow** out_window) {
    std::lock_guard<std::mutex> lock(g_active_->mutex_);
    auto it = g_active_->readers_.find(reader);
    if (it == g_active_->readers_.end()) {
      return AMEDIA_ERROR_INVALID_PARAMETER;
    }
    *out_window = it->second.window;
    return AMEDIA_OK;
  }

  static media_status_t ReaderAcquireAsync(AImageReader* reader,
                                           AImage** out_image,
                                           int* out_fence_fd) {
    std::lock_guard<std::mutex> lock(g_active_->mutex_);
    auto it = g_active_->readers_.find(reader);
    if (it == g_active_->readers_.end() || it->second.pending_images.empty()) {
      return AMEDIA_IMGREADER_NO_BUFFER_AVAILABLE;
    }
    while (it->second.pending_images.size() > 1) {
      auto dropped = it->second.pending_images.front();
      it->second.pending_images.pop_front();
      if (dropped.second >= 0) {
        ::close(dropped.second);
      }
      g_active_->images_.erase(dropped.first);
      g_active_->deleted_images_.fetch_add(1, std::memory_order_release);
    }
    auto latest = it->second.pending_images.front();
    it->second.pending_images.pop_front();
    *out_image = latest.first;
    *out_fence_fd = latest.second;
    return AMEDIA_OK;
  }

  static media_status_t ReaderSetImageListener(
      AImageReader* reader,
      AImageReader_ImageListener* listener) {
    std::lock_guard<std::mutex> lock(g_active_->mutex_);
    auto it = g_active_->readers_.find(reader);
    if (it == g_active_->readers_.end()) {
      return AMEDIA_ERROR_INVALID_PARAMETER;
    }
    if (listener == nullptr) {
      it->second.image_listener = {};
      it->second.has_image_listener = false;
    } else {
      it->second.image_listener = *listener;
      it->second.has_image_listener = true;
    }
    return AMEDIA_OK;
  }

  static media_status_t ReaderSetBufferRemovedListener(
      AImageReader* reader,
      AImageReader_BufferRemovedListener* listener) {
    std::lock_guard<std::mutex> lock(g_active_->mutex_);
    auto it = g_active_->readers_.find(reader);
    if (it == g_active_->readers_.end()) {
      return AMEDIA_ERROR_INVALID_PARAMETER;
    }
    if (listener == nullptr) {
      it->second.buffer_removed_listener = {};
      it->second.has_buffer_removed_listener = false;
    } else {
      it->second.buffer_removed_listener = *listener;
      it->second.has_buffer_removed_listener = true;
    }
    return AMEDIA_OK;
  }

  static void ImageDelete(AImage* image) {
    std::lock_guard<std::mutex> lock(g_active_->mutex_);
    g_active_->images_.erase(image);
    g_active_->deleted_images_.fetch_add(1, std::memory_order_release);
  }

  static void ImageDeleteAsync(AImage* image, int release_fence_fd) {
    if (release_fence_fd >= 0) {
      ::close(release_fence_fd);
    }
    ImageDelete(image);
  }

  static media_status_t ImageGetAhb(const AImage* image,
                                    AHardwareBuffer** out_ahb) {
    std::lock_guard<std::mutex> lock(g_active_->mutex_);
    auto it = g_active_->images_.find(const_cast<AImage*>(image));
    if (it == g_active_->images_.end() || it->second == nullptr) {
      return AMEDIA_ERROR_INVALID_PARAMETER;
    }
    *out_ahb = it->second;
    return AMEDIA_OK;
  }

  static FakeNdkEnvironment* g_active_;

  mutable std::mutex mutex_;
  uintptr_t next_ahb_addr_ = 0x10000;
  uintptr_t next_image_addr_ = 0x20000;
  uintptr_t next_reader_addr_ = 0x30000;
  uintptr_t next_window_addr_ = 0x40000;
  std::unordered_map<AHardwareBuffer*, FakeAhbEntry> ahbs_;
  std::unordered_map<AImage*, AHardwareBuffer*> images_;
  std::unordered_map<AImageReader*, FakeReaderEntry> readers_;
  std::vector<AImageReader*> created_readers_;
  std::atomic<int> deleted_images_{0};
  std::atomic<int> deleted_readers_{0};
  std::thread::id last_reader_delete_thread_;

  decltype(AHardwareBuffer_getId)* saved_ahb_get_id_ = nullptr;
  decltype(AHardwareBuffer_describe)* saved_ahb_describe_ = nullptr;
  decltype(AHardwareBuffer_acquire)* saved_ahb_acquire_ = nullptr;
  decltype(AHardwareBuffer_release)* saved_ahb_release_ = nullptr;
  decltype(AImageReader_newWithUsage)* saved_reader_new_ = nullptr;
  decltype(AImageReader_delete)* saved_reader_delete_ = nullptr;
  decltype(AImageReader_getWindow)* saved_reader_get_window_ = nullptr;
  decltype(AImageReader_acquireLatestImageAsync)* saved_reader_acquire_ =
      nullptr;
  decltype(AImageReader_setImageListener)* saved_reader_set_image_listener_ =
      nullptr;
  decltype(AImageReader_setBufferRemovedListener)*
      saved_reader_set_removed_listener_ = nullptr;
  decltype(AImage_delete)* saved_image_delete_ = nullptr;
  decltype(AImage_deleteAsync)* saved_image_delete_async_ = nullptr;
  decltype(AImage_getHardwareBuffer)* saved_image_get_ahb_ = nullptr;
};

FakeNdkEnvironment* FakeNdkEnvironment::g_active_ = nullptr;

class TestableImageExternalTextureGL final : public ImageExternalTextureGL {
 public:
  TestableImageExternalTextureGL(
      int64_t id,
      ImageLifecycle lifecycle,
      std::shared_ptr<NdkImageTextureBridge> ndk_bridge,
      std::atomic<int>* live_images)
      : ImageExternalTextureGL(id,
                               fml::jni::ScopedJavaGlobalRef<jobject>(),
                               nullptr,
                               lifecycle,
                               std::move(ndk_bridge)),
        live_images_(live_images) {}

  void InsertLegacyEntryForTest(HardwareBufferKey key, sk_sp<DlImage> image) {
    CacheImage(/*reader_generation=*/0, key, std::move(image));
  }

  int dl_create_count() const { return dl_create_count_; }

 protected:
  impeller::UniqueEGLImageKHR CreateEGLImage(AHardwareBuffer* buffer) override {
    if (buffer == nullptr) {
      return impeller::UniqueEGLImageKHR();
    }
    auto fake_img = reinterpret_cast<EGLImageKHR>(
        reinterpret_cast<uintptr_t>(buffer) ^ 0x55AA00ULL);
    return impeller::UniqueEGLImageKHR(
        impeller::EGLImageKHRWithDisplay{fake_img, EGL_NO_DISPLAY});
  }

  sk_sp<DlImage> CreateDlImage(
      PaintContext& /*context*/,
      const SkRect& bounds,
      impeller::UniqueEGLImageKHR&& egl_image) override {
    ++dl_create_count_;
    (void)egl_image.release();
    return sk_make_sp<CountingDlImage>(
        live_images_, DlISize(static_cast<int>(bounds.width()),
                              static_cast<int>(bounds.height())));
  }

 private:
  std::atomic<int>* live_images_;
  int dl_create_count_ = 0;
};

class TestableImageExternalTextureVK final
    : public ImageExternalTextureVKImpeller {
 public:
  TestableImageExternalTextureVK(
      int64_t id,
      ImageLifecycle lifecycle,
      std::shared_ptr<NdkImageTextureBridge> ndk_bridge,
      std::atomic<int>* live_images)
      : ImageExternalTextureVKImpeller(nullptr,
                                       id,
                                       fml::jni::ScopedJavaGlobalRef<jobject>(),
                                       nullptr,
                                       lifecycle,
                                       std::move(ndk_bridge)),
        live_images_(live_images) {}

  int import_count() const { return import_count_; }

 protected:
  sk_sp<DlImage> ImportHardwareBuffer(
      AHardwareBuffer* /*hardware_buffer*/,
      const AHardwareBuffer_Desc& desc) override {
    ++import_count_;
    return sk_make_sp<CountingDlImage>(
        live_images_,
        DlISize(static_cast<int>(desc.width), static_cast<int>(desc.height)));
  }

 private:
  std::atomic<int>* live_images_;
  int import_count_ = 0;
};

}  // namespace

TEST(NdkImageReaderTest, WaitAcquireFenceFdHandlesInvalidSignaledAndTimeout) {
  fml::UniqueFD invalid_fd;
  EXPECT_TRUE(WaitAcquireFenceFd(invalid_fd, 50));

  int signaled_pipe[2] = {-1, -1};
  ASSERT_EQ(::pipe(signaled_pipe), 0);
  ASSERT_EQ(::write(signaled_pipe[1], "x", 1), 1);
  ::close(signaled_pipe[1]);
  fml::UniqueFD signaled_fd(signaled_pipe[0]);
  EXPECT_TRUE(WaitAcquireFenceFd(signaled_fd, 100));
  EXPECT_FALSE(signaled_fd.is_valid());

  int unsignaled_pipe[2] = {-1, -1};
  ASSERT_EQ(::pipe(unsignaled_pipe), 0);
  fml::UniqueFD unsignaled_fd(unsignaled_pipe[0]);
  EXPECT_FALSE(WaitAcquireFenceFd(unsignaled_fd, 10));
  EXPECT_FALSE(unsignaled_fd.is_valid());
  ::close(unsignaled_pipe[1]);
}

TEST(NdkImageReaderTest,
     ReaderSchedulesFrameCapsOpenImagesAndEvictsRemovedBuffersWhilePaused) {
  FakeNdkEnvironment env;
  fml::Thread platform_thread("test_ndk_platform");
  fml::Thread raster_thread("test_ndk_raster");
  auto platform_runner = platform_thread.GetTaskRunner();
  auto raster_runner = raster_thread.GetTaskRunner();

  std::atomic<int> scheduled_frames{0};
  fml::AutoResetWaitableEvent frame_scheduled_event;
  auto bridge = std::make_shared<NdkImageTextureBridge>(
      42, fml::WeakPtr<PlatformView>(), platform_runner, raster_runner, [&]() {
        scheduled_frames.fetch_add(1, std::memory_order_release);
        frame_scheduled_event.Signal();
      });

  std::shared_ptr<NdkImageReader> reader;
  RunSyncOnTaskRunner(platform_runner, [&]() {
    reader = NdkImageReader::Create(nullptr, 320, 240, bridge);
  });
  ASSERT_NE(reader, nullptr);
  AImageReader* raw_reader = env.LastCreatedReader();
  ASSERT_NE(raw_reader, nullptr);
  auto snapshot = env.GetReaderSnapshot(raw_reader);
  EXPECT_EQ(snapshot.width, 320);
  EXPECT_EQ(snapshot.height, 240);
  EXPECT_EQ(snapshot.format, AIMAGE_FORMAT_PRIVATE);
  EXPECT_EQ(snapshot.usage, AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE);
  EXPECT_EQ(snapshot.max_images, NdkImageReader::kMaxImages);
  EXPECT_TRUE(snapshot.has_image_listener);
  EXPECT_TRUE(snapshot.has_buffer_removed_listener);

  std::atomic<int> live_images{0};
  std::shared_ptr<TestableImageExternalTextureGL> texture;
  RunSyncOnTaskRunner(raster_runner, [&]() {
    texture = std::make_shared<TestableImageExternalTextureGL>(
        42, ImageExternalTexture::ImageLifecycle::kKeepAlive, bridge,
        &live_images);
    bridge->BindTexture(texture);
  });

  AHardwareBuffer* buf1 = env.CreateFakeBuffer(101);
  AHardwareBuffer* buf2 = env.CreateFakeBuffer(102);
  AHardwareBuffer* buf3 = env.CreateFakeBuffer(103);
  AHardwareBuffer* buf4 = env.CreateFakeBuffer(104);

  auto paint_once = [&]() {
    RunSyncOnTaskRunner(raster_runner, [&]() {
      DisplayListBuilder builder;
      DlPaint paint;
      Texture::PaintContext ctx{.canvas = &builder, .paint = &paint};
      static_cast<Texture*>(texture.get())
          ->Paint(ctx, DlRect::MakeWH(100, 100), false,
                  DlImageSampling::kNearestNeighbor);
    });
  };

  env.EnqueueFakeImage(raw_reader, buf1);
  std::thread([&]() { env.FireImageAvailable(raw_reader); }).join();
  frame_scheduled_event.Wait();
  EXPECT_EQ(scheduled_frames.load(), 1);

  paint_once();
  env.EnqueueFakeImage(raw_reader, buf2);
  paint_once();
  EXPECT_EQ(reader->OpenImageCountForTest(), 2u);
  EXPECT_EQ(env.DeletedImageCount(), 0);

  env.EnqueueFakeImage(raw_reader, buf3);
  paint_once();
  EXPECT_EQ(reader->OpenImageCountForTest(), 2u);
  EXPECT_EQ(env.DeletedImageCount(), 1);

  env.EnqueueFakeImage(raw_reader, buf4);
  paint_once();
  EXPECT_EQ(reader->OpenImageCountForTest(), 2u);
  EXPECT_EQ(env.DeletedImageCount(), 2);

  RunSyncOnTaskRunner(raster_runner, [&]() {
    EXPECT_EQ(texture->CachedEntryCountForTest(), 4u);
  });

  // Fire onBufferRemoved while paused (zero Paint calls) and verify coalesced
  // raster-thread eviction.
  std::thread([&]() {
    env.FireBufferRemoved(raw_reader, buf1);
    env.FireBufferRemoved(raw_reader, buf2);
  }).join();

  RunSyncOnTaskRunner(raster_runner, [&]() {
    EXPECT_EQ(texture->CachedEntryCountForTest(), 2u);
    EXPECT_FALSE(texture->HasCachedKeyForTest({reader->generation(), 101}));
    EXPECT_FALSE(texture->HasCachedKeyForTest({reader->generation(), 102}));
    EXPECT_TRUE(texture->HasCachedKeyForTest({reader->generation(), 103}));
    EXPECT_TRUE(texture->HasCachedKeyForTest({reader->generation(), 104}));
  });

  RunSyncOnTaskRunner(platform_runner, [&]() {
    bridge->CloseAllReaders();
    reader.reset();
  });
  RunSyncOnTaskRunner(raster_runner, [&]() {
    static_cast<Texture*>(texture.get())->OnTextureUnregistered();
    texture.reset();
  });
  EXPECT_EQ(live_images.load(), 0);
}

TEST(NdkImageReaderTest,
     ResizeQueuePrunesSupersededReaderAndDeletesOnlyOnPlatformThread) {
  FakeNdkEnvironment env;
  fml::Thread platform_thread("test_resize_platform");
  fml::Thread raster_thread("test_resize_raster");
  auto platform_runner = platform_thread.GetTaskRunner();
  auto raster_runner = raster_thread.GetTaskRunner();

  std::thread::id platform_tid;
  RunSyncOnTaskRunner(platform_runner,
                      [&]() { platform_tid = std::this_thread::get_id(); });

  auto bridge = std::make_shared<NdkImageTextureBridge>(
      7, fml::WeakPtr<PlatformView>(), platform_runner, raster_runner);

  std::shared_ptr<NdkImageReader> reader1;
  std::shared_ptr<NdkImageReader> reader2;
  RunSyncOnTaskRunner(platform_runner, [&]() {
    reader1 = NdkImageReader::Create(nullptr, 100, 100, bridge);
  });
  AImageReader* raw1 = env.LastCreatedReader();
  RunSyncOnTaskRunner(platform_runner, [&]() {
    reader2 = NdkImageReader::Create(nullptr, 200, 200, bridge);
  });
  AImageReader* raw2 = env.LastCreatedReader();
  ASSERT_NE(reader1, nullptr);
  ASSERT_NE(reader2, nullptr);

  std::atomic<int> live_images{0};
  std::shared_ptr<TestableImageExternalTextureGL> texture;
  RunSyncOnTaskRunner(raster_runner, [&]() {
    texture = std::make_shared<TestableImageExternalTextureGL>(
        7, ImageExternalTexture::ImageLifecycle::kKeepAlive, bridge,
        &live_images);
    bridge->BindTexture(texture);
  });

  AHardwareBuffer* b_old = env.CreateFakeBuffer(501, 100, 100);
  AHardwareBuffer* b_new = env.CreateFakeBuffer(601, 200, 200);

  auto paint_once = [&]() {
    RunSyncOnTaskRunner(raster_runner, [&]() {
      DisplayListBuilder builder;
      DlPaint paint;
      Texture::PaintContext ctx{.canvas = &builder, .paint = &paint};
      static_cast<Texture*>(texture.get())
          ->Paint(ctx, DlRect::MakeWH(200, 200), false,
                  DlImageSampling::kLinear);
    });
  };

  env.EnqueueFakeImage(raw1, b_old);
  paint_once();
  EXPECT_FALSE(reader1->IsClosed());

  env.EnqueueFakeImage(raw2, b_new);
  paint_once();
  EXPECT_TRUE(reader1->IsClosed());
  EXPECT_FALSE(reader2->IsClosed());

  RunSyncOnTaskRunner(platform_runner, [&]() {});
  EXPECT_EQ(env.DeletedReaderCount(), 1);
  EXPECT_EQ(env.LastReaderDeleteThread(), platform_tid);

  RunSyncOnTaskRunner(raster_runner, [&]() {
    EXPECT_FALSE(texture->HasCachedKeyForTest({reader1->generation(), 501}));
    EXPECT_TRUE(texture->HasCachedKeyForTest({reader2->generation(), 601}));
    EXPECT_EQ(texture->CachedEntryCountForTest(), 1u);
  });

  RunSyncOnTaskRunner(platform_runner, [&]() {
    bridge->CloseAllReaders();
    reader1.reset();
    reader2.reset();
  });
  RunSyncOnTaskRunner(raster_runner, [&]() { texture.reset(); });
}

TEST(ImageExternalTextureCacheTest,
     SkipsCachingOnApi29WhenSystemUniqueIdUnavailable) {
  FakeNdkEnvironment env;
  env.SetApi31SystemIdEnabled(false);

  fml::Thread platform_thread("test_api29_platform");
  fml::Thread raster_thread("test_api29_raster");
  auto platform_runner = platform_thread.GetTaskRunner();
  auto raster_runner = raster_thread.GetTaskRunner();

  auto bridge = std::make_shared<NdkImageTextureBridge>(
      9, fml::WeakPtr<PlatformView>(), platform_runner, raster_runner);
  std::shared_ptr<NdkImageReader> reader;
  RunSyncOnTaskRunner(platform_runner, [&]() {
    reader = NdkImageReader::Create(nullptr, 64, 64, bridge);
  });
  AImageReader* raw_reader = env.LastCreatedReader();

  std::atomic<int> live_images{0};
  std::shared_ptr<TestableImageExternalTextureGL> texture;
  RunSyncOnTaskRunner(raster_runner, [&]() {
    texture = std::make_shared<TestableImageExternalTextureGL>(
        9, ImageExternalTexture::ImageLifecycle::kReset, bridge, &live_images);
    bridge->BindTexture(texture);
  });

  AHardwareBuffer* buf = env.CreateFakeBuffer(999);
  env.EnqueueFakeImage(raw_reader, buf);
  RunSyncOnTaskRunner(raster_runner, [&]() {
    DisplayListBuilder builder;
    DlPaint paint;
    Texture::PaintContext ctx{.canvas = &builder, .paint = &paint};
    static_cast<Texture*>(texture.get())
        ->Paint(ctx, DlRect::MakeWH(64, 64), false, DlImageSampling::kLinear);
    EXPECT_NE(texture->CurrentDlImageForTest(), nullptr);
    EXPECT_EQ(texture->CachedEntryCountForTest(), 0u);
  });

  RunSyncOnTaskRunner(platform_runner, [&]() {
    bridge->CloseAllReaders();
    reader.reset();
  });
  RunSyncOnTaskRunner(raster_runner, [&]() { texture.reset(); });
}

TEST(ImageExternalTextureCacheTest,
     GLAndVKReuseCachedImportsAndHonorContextDestroyedLifecycle) {
  FakeNdkEnvironment env;
  fml::Thread platform_thread("test_cache_platform");
  fml::Thread raster_thread("test_cache_raster");
  auto platform_runner = platform_thread.GetTaskRunner();
  auto raster_runner = raster_thread.GetTaskRunner();

  auto gl_bridge = std::make_shared<NdkImageTextureBridge>(
      20, fml::WeakPtr<PlatformView>(), platform_runner, raster_runner);
  auto vk_bridge = std::make_shared<NdkImageTextureBridge>(
      21, fml::WeakPtr<PlatformView>(), platform_runner, raster_runner);

  std::shared_ptr<NdkImageReader> gl_reader;
  std::shared_ptr<NdkImageReader> vk_reader;
  RunSyncOnTaskRunner(platform_runner, [&]() {
    gl_reader = NdkImageReader::Create(nullptr, 64, 64, gl_bridge);
  });
  AImageReader* raw_gl = env.LastCreatedReader();
  RunSyncOnTaskRunner(platform_runner, [&]() {
    vk_reader = NdkImageReader::Create(nullptr, 64, 64, vk_bridge);
  });
  AImageReader* raw_vk = env.LastCreatedReader();

  std::atomic<int> live_images{0};
  TextureRegistry registry;
  std::shared_ptr<TestableImageExternalTextureGL> keep_alive_gl;
  std::shared_ptr<TestableImageExternalTextureVK> reset_vk;
  RunSyncOnTaskRunner(raster_runner, [&]() {
    keep_alive_gl = std::make_shared<TestableImageExternalTextureGL>(
        20, ImageExternalTexture::ImageLifecycle::kKeepAlive, gl_bridge,
        &live_images);
    reset_vk = std::make_shared<TestableImageExternalTextureVK>(
        21, ImageExternalTexture::ImageLifecycle::kReset, vk_bridge,
        &live_images);
    gl_bridge->BindTexture(keep_alive_gl);
    vk_bridge->BindTexture(reset_vk);
    registry.RegisterTexture(keep_alive_gl);
    registry.RegisterTexture(reset_vk);
  });

  AHardwareBuffer* b1 = env.CreateFakeBuffer(1);
  AHardwareBuffer* b2 = env.CreateFakeBuffer(2);

  auto paint_tex = [&](Texture* t) {
    RunSyncOnTaskRunner(raster_runner, [&]() {
      DisplayListBuilder builder;
      DlPaint paint;
      Texture::PaintContext ctx{.canvas = &builder, .paint = &paint};
      t->Paint(ctx, DlRect::MakeWH(64, 64), false, DlImageSampling::kLinear);
    });
  };

  env.EnqueueFakeImage(raw_gl, b1);
  paint_tex(keep_alive_gl.get());
  env.EnqueueFakeImage(raw_gl, b2);
  paint_tex(keep_alive_gl.get());
  // Re-acquiring b1 hits the cache without re-creating DlImage.
  env.EnqueueFakeImage(raw_gl, b1);
  paint_tex(keep_alive_gl.get());

  int pipe_fd[2] = {-1, -1};
  ASSERT_EQ(::pipe(pipe_fd), 0);
  ASSERT_EQ(::write(pipe_fd[1], "z", 1), 1);
  ::close(pipe_fd[1]);
  env.EnqueueFakeImage(raw_vk, b1, pipe_fd[0]);
  paint_tex(reset_vk.get());
  env.EnqueueFakeImage(raw_vk, b1);
  paint_tex(reset_vk.get());

  RunSyncOnTaskRunner(raster_runner, [&]() {
    EXPECT_EQ(keep_alive_gl->dl_create_count(), 2);
    EXPECT_EQ(keep_alive_gl->CachedEntryCountForTest(), 2u);
    EXPECT_EQ(reset_vk->import_count(), 1);
    EXPECT_EQ(reset_vk->CachedEntryCountForTest(), 1u);

    registry.OnGrContextDestroyed();
    EXPECT_EQ(keep_alive_gl->CachedEntryCountForTest(), 0u);
    EXPECT_NE(keep_alive_gl->CurrentDlImageForTest(), nullptr);
    EXPECT_EQ(reset_vk->CachedEntryCountForTest(), 0u);
    EXPECT_EQ(reset_vk->CurrentDlImageForTest(), nullptr);
    EXPECT_EQ(live_images.load(), 1);

    registry.UnregisterTexture(20);
    registry.UnregisterTexture(21);
    keep_alive_gl.reset();
    reset_vk.reset();
  });
  EXPECT_EQ(live_images.load(), 0);

  RunSyncOnTaskRunner(platform_runner, [&]() {
    gl_bridge->CloseAllReaders();
    vk_bridge->CloseAllReaders();
    gl_reader.reset();
    vk_reader.reset();
  });
}

TEST(ImageExternalTextureCacheTest, LegacyGenerationZeroEnforcesBoundedSixCap) {
  std::atomic<int> live_images{0};
  TestableImageExternalTextureGL texture(
      30, ImageExternalTexture::ImageLifecycle::kReset, nullptr, &live_images);

  for (HardwareBufferKey i = 1; i <= 8; ++i) {
    texture.InsertLegacyEntryForTest(i,
                                     sk_make_sp<CountingDlImage>(&live_images));
  }
  EXPECT_EQ(texture.CachedEntryCountForTest(), 6u);
  EXPECT_FALSE(texture.HasCachedKeyForTest({0, 1}));
  EXPECT_FALSE(texture.HasCachedKeyForTest({0, 2}));
  EXPECT_TRUE(texture.HasCachedKeyForTest({0, 3}));
  EXPECT_TRUE(texture.HasCachedKeyForTest({0, 8}));
  EXPECT_EQ(live_images.load(), 6);
}

TEST(NdkImageReaderTest, RealDeviceNdkImageReaderEndToEndAcquireZeroJni) {
  if (!NdkImageReader::IsAvailable(/*require_java_surface=*/false)) {
    GTEST_SKIP() << "NDK AImageReader API 29+ procs not available.";
  }

  fml::Thread platform_thread("real_ndk_platform");
  fml::Thread raster_thread("real_ndk_raster");
  auto platform_runner = platform_thread.GetTaskRunner();
  auto raster_runner = raster_thread.GetTaskRunner();

  fml::AutoResetWaitableEvent image_available;
  auto bridge = std::make_shared<NdkImageTextureBridge>(
      99, fml::WeakPtr<PlatformView>(), platform_runner, raster_runner,
      [&]() { image_available.Signal(); });

  std::shared_ptr<NdkImageReader> reader;
  RunSyncOnTaskRunner(platform_runner, [&]() {
    reader = NdkImageReader::Create(nullptr, 64, 64, bridge);
  });
  ASSERT_NE(reader, nullptr);
  ASSERT_NE(reader->window(), nullptr);

  ANativeWindow_setBuffersGeometry(reader->window(), 64, 64,
                                   AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM);
  ANativeWindow_Buffer locked_buffer = {};
  ASSERT_EQ(ANativeWindow_lock(reader->window(), &locked_buffer, nullptr), 0);
  ASSERT_EQ(ANativeWindow_unlockAndPost(reader->window()), 0);

  EXPECT_FALSE(image_available.WaitWithTimeout(fml::TimeDelta::FromSeconds(3)));

  RunSyncOnTaskRunner(raster_runner, [&]() {
    auto acquired = bridge->AcquireLatestFromReaders();
    EXPECT_NE(acquired.hardware_buffer, nullptr);
    EXPECT_EQ(acquired.reader_generation, reader->generation());
    if (acquired.hardware_buffer != nullptr) {
      auto desc =
          impeller::android::HardwareBuffer::Describe(acquired.hardware_buffer);
      ASSERT_TRUE(desc.has_value());
      EXPECT_EQ(desc->width, 64u);
      EXPECT_EQ(desc->height, 64u);
    }
    EXPECT_TRUE(WaitAcquireFenceFd(acquired.acquire_fence_fd, 1000));
  });

  RunSyncOnTaskRunner(platform_runner, [&]() {
    bridge->CloseAllReaders();
    reader.reset();
  });
}

}  // namespace flutter::testing
