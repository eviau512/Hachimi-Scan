# 技术规格书 09：多帧 HDR 局部视差光流补偿与基准锚定时域防重影引擎规范 (Robust Parallax Optical Flow & Base-Anchored HDR De-ghosting Engine)

> **文档性质**：核心算法与工程架构技术规格书 (Technical Specification)  
> **制定目标**：彻底解决多帧 HDR / 曝光包围连拍在手持拍摄现实场景（含树叶、前景栏杆、移动车辆与行人等）时，由于三维空间视差（Depth Parallax）、树木微动及长曝光间隔造成的双重边缘轮廓（重影）与半透明鬼影问题。建立基于多层金字塔稠密光流（DIS Optical Flow）局部微调、基准帧绝对几何锚定（Base Frame Geometric Anchoring）与光度归一化鲁棒剔除（Photometric Robustness Rejection）的全链路无重影 HDR 引擎。

---

## 1. 物理机理与重影根因深度剖析 (Root Causes Analysis)

### 1.1 手持三维视差与全局二维单应性矩阵的不可调和性
* **视差几何原理**：真实三维场景中，前景物体（如距离镜头 $3 \sim 8\text{ m}$ 的树木、栏杆）与远景物体（如数百米外的桥梁、无限远处的云层）具有截然不同的深度 $Z$。
* **手持微平移引起的视差偏移**：手持连拍无法做到绝对围绕相机光心旋转，必然包含微小的空间平移向量 $\mathbf{T} = [T_x, T_y, T_z]^T$。像素在图像平面的视差漂移量为：
  $$\Delta x \approx f \cdot \frac{T_x}{Z}$$
* **全局单应性矩阵（Global Homography $H$）的局限**：$3 \times 3$ 单应性变换在透视几何上**严格仅适用于空间单一平面或纯旋转无平移场景**。当特征匹配算法以远景或地面为主平面拟合出 $H$ 后，处于不同深度的树叶、近景物体在经 $H$ 变换后在几何上**必然存在 $5 \sim 15\text{ px}$ 的物理错位**。

### 1.2 连拍时差与非刚体扰动 (Temporal Drift & Wind Motion)
* 传统调用完整拍照流的连拍跨度达到 $1.5 \sim 2\text{ s}$，室外微风足以引起树叶、枝干发生剧烈物理晃动；
* 道路行人和行驶车辆在长时差下产生数十甚至数百像素的非刚体位移。

### 1.3 拉普拉斯金字塔曝光融合对错位边缘的破坏性叠加
* Tom Mertens 多分辨率曝光融合算法基于拉普拉斯金字塔（Laplacian Pyramid）。高频差分层 $\mathbf{L}_l$ 存储了物体的高反差轮廓边缘。
* Mertens 算法默认输入各帧像素“几何绝对重合”。当基准帧的树叶边缘与辅助帧的树叶边缘错开若干像素时，金字塔高频层以约各 $50\%$ 的权重对两套边缘进行无条件线性叠加，导致锐利的单叶片变成了典型的“半透明双轮廓重影”。

---

## 2. 工业级防重影架构：三级空间与时域流水线 (Three-Tier Architecture)

本规范制定工业级三级流水线，彻底切断双重轮廓的产生途径：

```
[连拍输入序列: Frame 0 (基准帧), Frame 1..k (辅助曝光帧)]
                           │
                           ▼
  ┌────────────────────────────────────────────────────────┐
  │ 第一级：全局粗配准 (ORB + RANSAC Homography)            │
  │ • 提取尺度不变特征点，剔除离群点求解全局单应性矩阵 H     │
  │ • 对辅助帧实施全局透视反向映射: I_k_warped = W_H(I_k)  │
  └────────────────────────┬───────────────────────────────┘
                           │
                           ▼
  ┌────────────────────────────────────────────────────────┐
  │ 第二级：局部稠密光流视差补偿 (Pyramidal DIS Optical Flow)│
  │ • 构建灰度金字塔，运行稠密逆向搜索光流 (DIS PRESET_FAST)│
  │ • 捕获近景树木、栏杆与地面视差导致的微小局部非刚体流动  │
  │ • 对辅助帧实施局部向量重采样: I_k_dense = Remap(flow)   │
  └────────────────────────┬───────────────────────────────┘
                           │
                           ▼
  ┌────────────────────────────────────────────────────────┐
  │ 第三级：基准帧绝对几何锚定与光度鲁棒剔除                │
  │ • 光度归一化 (Photometric Normalization by EV Ratio)   │
  │ • 结构与高频梯度残差检测，生成逐像素鲁棒权重图 W_robust │
  │ • 权重守恒与基准帧回退: 凡错位/鬼影区 100% 锁死基准帧   │
  │ • 高光未饱和区无缝承接高动态范围 (Highlight Recovery)   │
  └────────────────────────┬───────────────────────────────┘
                           │
                           ▼
                 [最终超清、零重影 HDR 影像]
```

---

## 3. 核心算法数学模型 (Mathematical Formulations)

### 3.1 曝光光度归一化 (Photometric Normalization)
由于各帧之间曝光量不同（如基准帧 $\text{EV}_0 = 0$，辅助帧 $\text{EV}_1 = -2.0$），直接计算像素差值会因正常曝光差异而产生误判。
在未饱和且未严重欠曝的中灰度区域（$Y \in [30, 220]$），估算真实光度增益比：
$$s_k = \frac{\text{median}_{\Omega}(Y_0)}{\text{median}_{\Omega}(Y_k)}, \quad \Omega = \{ \mathbf{x} \mid 30 \le Y_0(\mathbf{x}) \le 220 \text{ 且 } 15 \le Y_k(\mathbf{x}) \le 200 \}$$
将对齐后的辅助帧亮度投影至基准帧光度尺度：
$$\tilde{Y}_k(\mathbf{x}) = \text{clamp}(Y_k(\mathbf{x}) \cdot s_k, 0, 255)$$

### 3.2 局部视差稠密光流场估计 (Dense Inverse Search Optical Flow)
为消除全局单应性无法匹配的三维视差，采用 DIS 算法：
$$\arg\min_{\mathbf{u}} \iint \left( I_k^{\text{warped}}(\mathbf{x} + \mathbf{u}(\mathbf{x})) - I_0(\mathbf{x}) \right)^2 + \lambda \|\nabla \mathbf{u}(\mathbf{x})\|^2 \, d\mathbf{x}$$
* 使用 1/2 或 1/4 金字塔尺度快速计算高密度二维位移场 $\mathbf{u}(\mathbf{x}) = (u(\mathbf{x}), v(\mathbf{x}))$；
* 双线性重映射生成消解视差后的辅助帧图像 $I_k^{\text{dense}}(\mathbf{x})$。

### 3.3 像素级时域鲁棒性防重影掩模 (Temporal Robustness Weight)
在光流对齐后，对于依然存在的树叶剧烈风摆、移动行人或遮挡：
1. **光度残差度量**：
   $$\Delta_{\text{photo}}(\mathbf{x}) = |\tilde{Y}_k(\mathbf{x}) - Y_0(\mathbf{x})|$$
2. **边缘与梯度方向一致性度量**：
   计算 Sobel 梯度幅值与方向差，捕捉树叶轮廓与树枝边缘错位：
   $$\Delta_{\text{grad}}(\mathbf{x}) = \|\nabla Y_0(\mathbf{x}) - \nabla \tilde{Y}_k(\mathbf{x})\|$$
3. **复合结构残差**：
   $$R_k(\mathbf{x}) = \Delta_{\text{photo}}(\mathbf{x}) + \beta \cdot \Delta_{\text{grad}}(\mathbf{x})$$
4. **鲁棒衰减权重**：
   $$W_{\text{robust}, k}(\mathbf{x}) = \exp\left( - \frac{R_k(\mathbf{x})^2}{2 \sigma_{\text{motion}}^2} \right)$$
   门限设置：$\sigma_{\text{motion}} \approx 25.0$。当边缘错位大于容差时，$W_{\text{robust}, k}(\mathbf{x}) \to 0$。

### 3.4 高光保护与死白特化豁免 (Specular & Saturation Exemption)
当基准帧本身发生强光过曝截断（如白色发光屏幕、直射高光天空，$Y_0(\mathbf{x}) > 235$）时：
* 基准帧自身的高频边缘信息已被打白饱和截断；
* 此时辅助帧（低曝光帧）具有唯一真实的文字或云层层次；
* 豁免规则：若 $Y_0(\mathbf{x}) > 235$ 且 $Y_k(\mathbf{x}) < 230$，强行令 $W_{\text{robust}, k}(\mathbf{x}) \ge 0.8$，确保高动态范围的亮部信息不被误当成鬼影丢弃。

### 3.5 权重守恒与基准帧回退律 (Weight Conservation Law)
为确保画面亮度绝对平滑、绝不引入空洞或斑驳：
* 归一化融合权重满足拉普拉斯/泊松守恒：
  $$W_k^{\text{norm}}(\mathbf{x}) = W_k^{\text{Mertens}}(\mathbf{x}) \cdot W_{\text{robust}, k}(\mathbf{x}), \quad \forall k \ge 1$$
* **基准帧吸纳一切被剔除的辅助权重**：
  $$W_0^{\text{final}}(\mathbf{x}) = 1.0 - \sum_{k=1}^N W_k^{\text{norm}}(\mathbf{x})$$
* **数学推论**：在任何错位发生处（$W_{\text{robust}, k} \approx 0$），$W_0^{\text{final}} \to 1.0$。合成结果在该像素**在数学上等价于单张纯净基准帧，重影概率恒为 0**！

---

## 4. C++ 核心工程落地架构 (`burst_fusion.cpp`)

### 4.1 函数与数据流设计
1. `bool alignFrameHomography(const cv::Mat& src, const cv::Mat& ref, cv::Mat& outWarped, cv::Mat& outH)`
   - 提取 ORB 特征，以最长边 1280 尺度计算全局刚体配准；
2. `bool refineOpticalFlowDIS(const cv::Mat& srcWarped, const cv::Mat& ref, cv::Mat& outRefined)`
   - 使用 `cv::DISOpticalFlow` 执行金字塔快速局部光流微调，消除树叶与前景视差；
3. `cv::Mat computeRobustDeghostMask(const cv::Mat& candRefined, const cv::Mat& baseFrame)`
   - 计算光度比率归一化，生成 0.0~1.0 软边缘抗重影遮罩；
4. `cv::Mat blendRobustHDR(const cv::Mat& baseFrame, const std::vector<cv::Mat>& alignedFrames, const std::vector<cv::Mat>& robustMasks)`
   - 结合多分辨率金字塔与保边引导滤波（Guided Filter）重构最终画质。

---

## 5. 验收标准与测试规范 (Acceptance Criteria)

1. **静物与树叶重影验证**：
   - 在室外强对比度逆光场景（树叶对天空、栏杆对桥梁）下拍摄，放大 300% 观察树梢叶片，单叶片边缘锐利，**杜绝任何平行位移双轮廓**；
2. **动态干扰剔除**：
   - 画面中走动的行人和行驶的车辆保持单重实体，**杜绝半透明双重人影/车影**；
3. **动态范围扩展（HDR）**：
   - 阴天云层暗部层次与背光暗区细节完整展现，保持通透自然；
4. **性能指标**：
   - 在高通骁龙主流平台（如骁龙 7/8 系），整套对齐、光流与融合总耗时控制在 $600 \sim 900\text{ ms}$ 内，UI 无卡死与无响应（ANR）。

---

## 6. 学术溯源与洁净室合规声明 (Provenance & Clean-Room Statement)

* **算法学术文献溯源**：
  1. Samuel W. Hasinoff et al. *"Burst photography for high dynamic range and low-light imaging on mobile cameras"*, ACM Trans. Graph. (Proc. SIGGRAPH Asia 2016). (Google HDR+ 核心原理与时域鲁棒剔除模型)
  2. Till Kroeger, Radu Timofte, Dengxin Dai, Luc Van Gool. *"Fast Optical Flow using Dense Inverse Search"*, European Conference on Computer Vision (ECCV 2016). (DIS 稠密反向搜索光流模型)
  3. Tom Mertens, Jan Kautz, Frank Van Reeth. *"Exposure Fusion"*, Computer Graphics Forum (2007).
* **代码合规声明**：
  本引擎全链路代码基于公开学术论文与通用 OpenCV 开源算法库（BSD 3-Clause）洁净室独立撰写，未参考、反编译或包含任何 MotoCam / GCam / 厂商私有二进制组件或专有闭源资产。
