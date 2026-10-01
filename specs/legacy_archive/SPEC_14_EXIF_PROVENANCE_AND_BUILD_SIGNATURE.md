# 技术规格书 14：EXIF 摄影参数溯源与哈基米 Build 指纹注入规范 (EXIF Provenance & Hachimi Build Signature Specification)

> **文档性质**：元数据流转与构建溯源工程规范 (Metadata Architecture & Provenance Specification)  
> **制定目标**：彻底解决裁切、透视变换与导出过程中丢弃 EXIF 摄影参数的缺陷，全面对标专业相机（如索尼 Alpha 系列、摩托罗拉 MotoCam）的元数据管线，自动从 Git 仓库提取唯一的 short commit hash 作为 Build ID，将完整的硬件摄影参数（快门、ISO、焦距、光圈、白平衡）与哈基米引擎版本指纹（Software、ImageUniqueID、UserComment）全链路透传并烙印在导出的 JPEG 文件中。

---

## 1. 现状成因分析 (Root Cause Analysis)

### 1.1 OpenCV I/O 阻断 EXIF 流转
在当前工程中：
1. `CameraViewModel.kt` 的单帧模式直接保存 CameraX 原始 JPEG，但未打上哈基米引擎指纹；
2. `CropViewModel.kt` 在执行文档透视校正与色彩滤镜时，通过 `Imgcodecs.imwrite(croppedFile.absolutePath, warpedMat, ...)` 保存中间图，丢失了原始硬件帧的所有 EXIF 标签；
3. `FolderExporter.kt` 以字节流形式（`FileInputStream.copyTo(...)`）导出 `page.imagePath`，致使最终外部存储的图片缺少 EXIF 元数据，导致 Google 相册、文件管理器与相片分析器无法读取拍摄参数与构建来源。

---

## 2. 核心架构与工程设计 (Architecture Design)

### 2.1 Gradle 构建期 Git 提交指纹注入
在 `app/build.gradle.kts` 中配置动态构建字段：
1. 启用 `buildFeatures { buildConfig = true }`；
2. 在构建时通过 `git rev-parse --short HEAD` 获取当前代码仓库的唯一提交哈希：
   - `BuildConfig.GIT_HASH`: 7 位短哈希（例如 `ba26e65`）；
   - `BuildConfig.BUILD_TIME`: 构建时间戳（例如 `2026-09-29 20:30`）。

### 2.2 全链路 EXIF 透传与哈基米指纹注入工具类 (`ExifUtils`)
建立统一元数据处理工具 `com.scanner.app.data.util.ExifUtils`：
- **指纹注入规则**：
  - `TAG_SOFTWARE`: 写入 `Hachimi Cam (${BuildConfig.GIT_HASH})`
  - `TAG_IMAGE_UNIQUE_ID`: 写入 `${BuildConfig.GIT_HASH}`
  - `TAG_USER_COMMENT`: 写入 `Hachimi Scan Engine (Build: ${BuildConfig.GIT_HASH}, ${BuildConfig.BUILD_TIME})`
  - `TAG_MAKE`: 保留或回退至 `Build.MANUFACTURER`
  - `TAG_MODEL`: 保留或回退至 `Build.MODEL`
- **核心光学参数克隆清单**（100% 保持相机硬件原生采样）：
  - `TAG_EXPOSURE_TIME`（快门曝光时间）
  - `TAG_PHOTOGRAPHIC_SENSITIVITY`（ISO 感光度）
  - `TAG_F_NUMBER`（镜头光圈）
  - `TAG_FOCAL_LENGTH`（焦距）
  - `TAG_WHITE_BALANCE`（白平衡）
  - `TAG_DATETIME`, `TAG_DATETIME_ORIGINAL`, `TAG_DATETIME_DIGITIZED`
  - `TAG_FLASH`, `TAG_COLOR_SPACE`
  - `TAG_ORIENTATION`（规范化方向）

### 2.3 关键流转节点改造
1. **拍照产物（`CameraViewModel.kt`）**：
   - 单帧捕获：使用 `ExifUtils.stampHachimiSignature(photoFile)`；
   - 多帧融合：从 `tempFiles[0]` 完整克隆元数据至 `finalPhotoFile`，并注入哈基米指纹。
2. **文档裁切与后处理（`CropViewModel.kt`）**：
   - `confirmCrop` 在写入 `croppedFile` 后，立即调用 `ExifUtils.copyExif(originalFile, croppedFile)`，确保裁剪和滤镜后的图片依然拥有完整的拍摄与构建信息。
3. **最终文件导出（`FolderExporter.kt`）**：
   - 针对 Android Q+（Scoped Storage）：使用 `resolver.openFileDescriptor(uri, "rw")` 获得 `FileDescriptor`，通过 `ExifInterface(fd)` 写入并 `saveAttributes()`；
   - 针对旧版系统：直接对目标文件路径进行 `ExifInterface` 转录与保存。

---

## 3. 验收与交付规范
1. `specs/SPEC_14_EXIF_PROVENANCE_AND_BUILD_SIGNATURE.md` 入库。
2. 导出的图片在系统文件管理器 / Google 相册的“图片详细信息”中，可直接查看到快门、ISO、焦距，以及软件版本标识（如 `Hachimi Cam (ba26e65)`）。
3. 执行 `./gradlew assembleHachimiRelease` 验证无编译错误。
4. 将最新 Release 包通过 Taildrop 推送至 `hyper-obs:`。
5. 提交至 Git 仓库，严禁创建或修改任何 GitHub Release / Tag。
