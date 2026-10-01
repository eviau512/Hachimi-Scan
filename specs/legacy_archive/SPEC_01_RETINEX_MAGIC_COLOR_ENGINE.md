# 技术规格书 01：Retinex 图像处理引擎规范 (Retinex Enhancement Engine Specification)

> **文档说明**：为文档色彩增强、阴影消除与快速透视校正提供数学模型与接口协议规范。

---

## 1. 坐标体系与几何规范 (Geometry & Coordinates)

### 1.1 四边形角点数据结构 (Quad Layout)
文档检测输出与裁剪输入必须严格遵循以下 **顺时针顺序** 的 4 角点定义：
* `P0 (Top-Left)`: 左上角坐标 $(x_0, y_0)$
* `P1 (Top-Right)`: 右上角坐标 $(x_1, y_1)$
* `P2 (Bottom-Right)`: 右下角坐标 $(x_2, y_2)$
* `P3 (Bottom-Left)`: 左下角坐标 $(x_3, y_3)$

存储形式为扁平化浮点或整数数组：
$$\text{QuadArray} = [x_0, y_0, x_1, y_1, x_2, y_2, x_3, y_3]$$

### 1.2 质心极角排序法则 (Centroid Polar Angle Sorting)
为了彻底根治因文档倾斜（如顺时针倾斜 30°~60°）导致的沙漏交叉错乱，必须废弃 $x+y$ 极值法，采用质心极角排序：
1. 计算候选 4 点几何中心（Centroid）：
   $$C_x = \frac{1}{4}\sum_{i=0}^3 x_i, \quad C_y = \frac{1}{4}\sum_{i=0}^3 y_i$$
2. 计算每个顶点相对于质心的极角：
   $$\theta_i = \text{atan2}(y_i - C_y, x_i - C_x)$$
3. 依据极角由小到大排序得到凸多边形环绕序列。
4. 寻找最小 $x+y$ 或距离左上原点最近的点作为 $P_0$（Top-Left），后续点顺时针依次确定为 $P_1, P_2, P_3$。

---

## 2. 取景预览低开销检测规范 (Preview Optimization)

### 2.1 Y 平面直通通道 (Y-Plane Direct Access)
在 CameraX 预览阶段（30 FPS / 60 FPS），严禁在 Java/Kotlin 端将 `YUV_420_888` 转换为全彩色 RGB Bitmap。
* **规则**：仅提取 `ImageProxy.planes[0]`（即灰度 Y 通道，尺寸为 $W \times H$）。
* **内存传递**：通过 JNI 直接指针传递，实现零拷贝（Zero-Copy）或单次内存映射。
* **算法下采样**：限制送入检测器的长边不大于 640 像素。

### 2.2 复杂低对比度场景下的多尺度自适应边缘检测 (Adaptive Low-Contrast Detection)
针对墙面白板、浅色桌面白纸等弱对比度目标：
1. **对比度受限自适应直方图均衡化 (CLAHE)**：
   在 Canny 边缘检测前，对灰度图执行 CLAHE（$clipLimit = 2.0$, $tileGridSize = (8, 8)$），显著拉伸微小灰度反差（如白墙与白纸之间 $\Delta I \approx 15 \sim 25$ 的跃变）。
2. **敏感双阈值策略**：
   严禁在含有复杂大面积明暗对比的场景（如亮墙+深色桌椅）中使用全局 Otsu 阈值（会导致全局阈值被深色家具拉高，抹杀浅色白板边缘）。必须采用自适应 CLAHE 结合固定敏感阈值：
   $$T_{low} = 25.0, \quad T_{high} = 70.0$$
3. **目标面积门限放宽**：
   现实中远距离墙面告示、桌面票据、名片等目标占画幅比例通常仅为 $1\% \sim 3\%$。
   最小轮廓面积门限必须放宽至：
   $$\text{Area}_{min} = 0.008 \times \text{FrameArea} \quad (\approx 0.8\%)$$
   最大面积门限 $\text{Area}_{max} = 0.95 \times \text{FrameArea}$。
4. **多尺度轮廓候选评分**：
   不仅按面积排序，更需结合凸四边形长宽比合理性（$0.2 \le \text{Aspect} \le 5.0$）、矩形度（$\frac{\text{Area}}{\text{RotatedRectArea}} \ge 0.75$）进行几何凸性加权评分，优先挑选画面中央高置信度多边形。

---

## 3. “魔术彩色”光照补偿与去阴影数学模型 (Magic Color Engine)

### 3.1 物理光学成像模型 (Retinex Formation Model)
拍摄的图像信号 $S(x, y)$ 是场景固有反射率 $R(x, y)$（包含文字、印章、纸张本色）与环境照度分布场 $L(x, y)$（包含不均匀台灯光照、手机投下的阴影）的点积：
$$S(x, y) = R(x, y) \times L(x, y)$$

* **目标**：从 $S(x, y)$ 中估计出低频平滑的照度场 $L(x, y)$，通过除法运算还原纯净反射率 $R(x, y)$：
  $$R(x, y) = \frac{S(x, y)}{L(x, y)} \times 255.0$$

### 3.2 离散算法执行流水线 (Discrete Algorithm Steps)
1. **色彩空间解耦 (CIE-Lab 空间)**：
   * 将经过透视校正的输入图像由 BGR 转换至 **CIE-Lab** 色彩空间。
   * 分离出亮度通道 $L$（Lightness）与色度通道 $a, b$（Chrominance）。
   * *核心原则*：照度除法**仅在亮度通道 $L$ 上执行**，$a, b$ 通道保持不变。这能确保红色公章、蓝色钢笔墨水、荧光笔高亮等色彩信息完全不受光照估计失真的破坏。

2. **低频光照估计 (Illumination Estimation)**：
   * 对亮度通道执行大核形态学膨胀操作（`cv::dilate`），结构元素推荐为矩形核，尺寸为图像长边的约 $3\% \sim 5\%$（例如 $21 \times 21$ 或 $31 \times 31$）。膨胀操作能够有效“抹平”深色文字笔画，提取出连续的纸张底色与阴影过渡。
   * 对膨胀后的图应用大核中值滤波（`cv::medianBlur`）或引导滤波（Guided Filter），彻底消除膨胀产生的阶梯效应，得到平滑的背景照度估计图 $L_{est}$。

3. **照度除法与归一化 (Illumination Division)**：
   * 为防止除以零，对 $L_{est}$ 做下限截断（$\epsilon = 1.0$）：
     $$L_{safe}(x, y) = \max(L_{est}(x, y), 1.0)$$
   * 执行矩阵除法：
     $$L_{corrected}(x, y) = \text{clamp}\left( \frac{L(x, y)}{L_{safe}(x, y)} \times 255.0, \ 0, \ 255 \right)$$

4. **纸面白底截断与对比度增强 (Background Pure-White Clamping)**：
   * 现实纸张并非绝对平整，背景除法后通常处于 $230 \sim 255$ 区间。
   * 设定高光阈值 $T_{white} = 235$。对所有 $L_{corrected} > T_{white}$ 的像素，线性映射至纯白 255：
     $$L_{final} = \begin{cases} 255 & \text{if } L_{corrected} \ge T_{white} \\ \frac{L_{corrected}}{T_{white}} \times 255 & \text{if } L_{corrected} < T_{white} \end{cases}$$

5. **通道重组与还原**：
   * 将计算完毕的 $L_{final}$ 与原始的 $a, b$ 通道合并，转换回 BGR/RGB 色彩空间。
   * 最终图像呈现效果：底色纯白洁净、阴影彻底消失、文字边缘锐利、彩色印章与笔记色彩鲜亮饱满。

---

## 4. 导出压缩规范 (Export Compression)
* 导出模块推荐集成 **mozjpeg** 算法或采用平滑 DCT 量化表；
* 在导出 80% 质量时，生成的 JPEG 文件体积比原生 Android Bitmap Compress 小 15%~20%，且无振铃伪影。
