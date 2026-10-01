# HachiCam Architecture & Technical Specifications
## HachiCam 技术规格书体系索引

> **System Status**: Refactored & Consolidated (v10 / 2026-10-02)  
> **Repository**: [eviau512/Hachimi-Scan](https://github.com/eviau512/Hachimi-Scan)  
> **Language Policy**: Primary English with bilingual (English + Chinese) executive summaries.

---

## 1. Specification Index / 规格书全景索引

The technical documentation has been refactored from 18 fragmented, iterative notes into 6 authoritative, non-overlapping specifications:

| Spec Document | Title | Core Scope | Legacy Specs Superseded |
|:---|:---|:---|:---|
| **[`SPEC_00`](./SPEC_00_ARCHITECTURE.md)** | **System Architecture & Tech Stack** | System principles (privacy, de-Googled, math CV), technology stack, subsystem layering, directory layout, and SLAs. | `SPEC_00_ARCHITECTURE_AND_TECH_STACK.md` |
| **[`SPEC_01`](./SPEC_01_CAMERA_AND_CAPTURE_PIPELINE.md)** | **Camera Subsystem & Capture Pipeline** | CameraX 1.4 binding, Camera2Interop ISP tuning, tap-to-focus/metering (AF/AE), stability detection, Camera2 direct manual exposure injection, and EXIF provenance. | `SPEC_11`, `SPEC_14`, `SPEC_16` |
| **[`SPEC_02`](./SPEC_02_COMPUTATIONAL_PHOTOGRAPHY_AND_VULKAN_HDR.md)** | **Computational Photography & Vulkan GPU HDR** | 4-frame 50MP super-resolution canvas, ORB/RANSAC homography, Vulkan compute shader (`vulkan_hdr_ltm.comp`), tone-mapped highlight reconstruction ($[175, 245]$), fast LTM, and cinematic S-curve. | `SPEC_04`, `SPEC_08`, `SPEC_09`, `SPEC_10`, `SPEC_12`, `SPEC_13`, `SPEC_17` |
| **[`SPEC_03`](./SPEC_03_DOCUMENT_DETECTION_AND_INTERACTIVE_CROP.md)** | **Document Detection & Interactive Cropping** | Real-time preview Sobel integral-image text saliency density, 12:1 wide aspect ratio support, LSD magnetic snapping, and 60 FPS QuadCropView with 2.8× loupe. | `SPEC_05`, `SPEC_06`, `SPEC_15` |
| **[`SPEC_04`](./SPEC_04_IMAGE_ENHANCEMENT_AND_GEOMETRY.md)** | **Document Enhancement Filters & Geometry** | Perspective warp with pitch angle compensation, CIE-Lab Retinex Magic Color illumination division, Sauvola adaptive binarization, and book curve dewarping. | `SPEC_01`, `SPEC_02`, `SPEC_03` |
| **[`SPEC_05`](./SPEC_05_HACHIMI_DESIGN_SYSTEM.md)** | **Hachimi Design System & User Experience** | Brand visual tokens (Teal `#008080`, Cherry Pink `#FFB7C5`, AMOLED Dark), Material 3 layout, viewfinder controls, and review carousel. | `SPEC_07` |

---

## 2. Legacy Archive / 历史规格书归档

All prior incremental developmental notes and preliminary experiments (`SPEC_00` through `SPEC_17`) have been preserved in the [`legacy_archive/`](./legacy_archive/) directory for archaeological reference, historical audit trails, and patent/provenance tracking.
