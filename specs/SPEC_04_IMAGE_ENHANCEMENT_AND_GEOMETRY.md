# Specification 04: Document Enhancement Filters & Geometry
## 技术规格书 04：文档图像增强滤镜与几何矫正规范

> **Document Status**: Authoritative Subsystem Specification  
> **Consolidates**: Legacy SPEC_01 (Retinex Magic Color), SPEC_02 (Adaptive Binarization), and SPEC_03 (Curve Dewarping & Inpainting)  
> **Target Audience**: Image Processing & Computational Vision Engineers  
> **Languages**: Primary: English / Secondary: Chinese (Bilingual Executive Summaries)

---

## 1. Overview / 概述

Once a physical document is cropped, the image undergoes geometric rectification, illumination normalization, and selective document filtering (Magic Color, B&W, Grayscale, Sauvola Binarization, or Curved Page Flattening).

---

## 2. Perspective Warp Rectification / 透视变换与几何矫正

Given four document corner points $(P_0, P_1, P_2, P_3)$ sorted in clockwise order (top-left, top-right, bottom-right, bottom-left):

1. **Target Dimensions Calculation**:
   $$W_{\text{target}} = \max(\|P_1 - P_0\|, \|P_2 - P_3\|)$$
   $$H_{\text{target}} = \max(\|P_3 - P_0\|, \|P_2 - P_1\|)$$
2. **Homography Solution & Warp**:
   The $3 \times 3$ perspective transformation matrix $M$ is solved via `cv::getPerspectiveTransform`. The image is projected via `cv::warpPerspective` using `cv::INTER_CUBIC` interpolation.
3. **Vertical Pitch Compensation**:
   When capturing documents on a desk with an oblique phone tilt, vertical foreshortening is compensated to restore true aspect ratio (e.g. A4 $\sqrt{2} \approx 1.414$).

---

## 3. Retinex "Magic Color" Illumination Normalization / Retinex 炫彩滤镜

Document shadows and uneven lighting (e.g. hand shadows, room ceiling light gradients) are removed using Retinex theory in the CIE-Lab color space:

```mermaid
graph LR
    Input["Input BGR Document"] --> Lab["Convert to CIE-Lab Space"]
    Lab --> Sep["Separate L*, a*, b* Channels"]
    Sep --> Illum["Large Gaussian Blur on L* (Illumination Background)"]
    Illum --> Div["Retinex Division: L* / L_bg * 255"]
    Div --> Stretch["Contrast S-Curve & White Balance"]
    Stretch --> Merge["Recombine L*, a*, b* & Convert to BGR"]
    Merge --> Output["Pristine Shadow-Free Output"]
```

### 3.1 Mathematical Formulation
1. **Background Illumination Estimation**:
   A large spatial Gaussian kernel ($\sigma = \max(W, H) / 25$) estimates the low-frequency illumination field $L_{\text{bg}}$ on the luminance channel $L^*$:
   $$L_{\text{bg}} = L^* \ast G_\sigma$$
2. **Reflectance Division**:
   The true physical surface reflectance is recovered by dividing luminance by the estimated illumination field:
   $$L_{\text{norm}}(x, y) = \text{clamp}\left(\frac{L^*(x, y)}{\max(L_{\text{bg}}(x, y), 1.0)} \cdot 220.0, 0.0, 255.0\right)$$
3. **White Background Normalization**:
   Pixels where $L_{\text{norm}} \ge 215$ are pulled smoothly toward pure paper white ($255$), while retaining ink stroke vibrancy in $a^*$ and $b^*$ chromatic channels.

---

## 4. Sauvola Adaptive Binarization Engine / Sauvola 自适应二值化

For archival black-and-white documents, receipts, and contract scanning, the engine implements Sauvola thresholding accelerated by double-precision integral images:

### 4.1 Integral Image Acceleration
Using single-pass integral images for local sum and squared sum:
$$I_{\text{sum}}(X, Y) = \sum_{x \le X, y \le Y} I(x, y), \quad I_{\text{sq\_sum}}(X, Y) = \sum_{x \le X, y \le Y} I(x, y)^2$$
Local mean $m(x, y)$ and standard deviation $s(x, y)$ for any window size $W \times W$ are evaluated in $O(1)$ constant time.

### 4.2 Threshold Equation
$$T(x, y) = m(x, y) \cdot \left[ 1.0 + k \cdot \left(\frac{s(x, y)}{R} - 1.0\right) \right]$$
Where:
- $k = 0.20$ (sensitivity factor balancing thin strokes against background noise);
- $R = 128.0$ (dynamic range constant for 8-bit grayscale);
- Window size $W = 31$ pixels.
- Output: Binary mask where $I(x, y) \le T(x, y) \implies 0$ (black ink), otherwise $255$ (pure white paper).

---

## 5. Cylindrical Book Surface Dewarping & Fingerprint Inpainting / 书籍曲面展平与去手指

When scanning thick bound books:
1. **Baseline Tracking**:
   Horizontal text lines are tracked across the document page to extract horizontal curvature displacement vectors.
2. **Cylindrical Mesh Transformation**:
   A 2D vertical mesh coordinates backward mapping from the curved surface back into an unrolled flat plane.
3. **Fingerprint Mask Detection & Inpainting**:
   Fingers holding book edges are detected by skin tone segmentation in YCrCb color space. The occluded text strokes and paper texture are reconstructed using Fast Marching Method (Telea) image inpainting (`cv::inpaint`).
