# 技术规格书 15：大长宽比标牌识别与内部文本显著度检测规范 (Text Saliency & Wide-Aspect Detection Specification)

> **文档性质**：算法核心特性规范说明书 (Algorithm Core Specification)  
> **制定目标**：解决传统文档扫描算法在真实复杂交通与生活场景中无法识别大长宽比文档（如地铁站牌、条幅、车厢线路图、长票据）以及误检平坦反光玻璃门窗的技术痛点。

---

## 1. 业务痛点与物理场景分析 (Pain Points)

### 1.1 场景差异与传统算法退化
在复杂公共交通场景（如地铁站台、车厢）或商场内部：
1. **极限长宽比**：地铁站点标牌（如“博览中心站 / 4号线”）长宽比普遍在 $6:1 \sim 9:1$，传统文档扫描算法设定的长宽比上限（如 $\le 5.0$）会将其当做“线性杂噪”直接抛弃；
2. **平坦玻璃门的高对比误检**：地铁车门上的车窗在暗光隧道内由于内外高反差拥有极其强烈的外部几何轮廓边缘，但其内部为纯净平坦玻璃（无文字笔画）；传统基于外部轮廓凸度与面积的算法极易将车窗误当做扫描目标，导致漏检站牌。

---

## 2. 算法原理与实现规范 (Algorithmic Principles)

### 2.1 长宽比门限放宽
候选多边形最小外接矩形长宽比由 $5.0$ 放宽至 $12.0$：
$$\text{Aspect Ratio} = \frac{\max(W, H)}{\min(W, H)} \le 12.0$$
同时将矩形度（Rectangularity）门限调整至 $0.65$，适应由于仰拍、俯拍引起的轻微梯形畸变。

### 2.2 基于 Sobel 积分图的内部笔画显著度评估 (Text Saliency)
为区分“空白玻璃窗”与“写满站名与线路的高频信息标牌”，算法引入内部笔画高频能量快速评估：

1. **梯度幅值场生成**：
   在缩小预览灰度图 $I_{\text{small}}$ 上计算水平与垂直 Sobel 导数：
   $$\text{gradMag}(x, y) = |\text{Sobel}_x(I)| + |\text{Sobel}_y(I)|$$
2. **积分图构建 (Integral Image)**：
   将梯度幅值场构建为 64 位双精度积分图 $S_{\text{grad}}$：
   $$S_{\text{grad}}(X, Y) = \sum_{x \le X, y \le Y} \text{gradMag}(x, y)$$
3. **多边形内缩区域积分抽样**：
   对候选多边形包围盒向内收缩 $12\%$ 安全边距（排除边框自身高对比梯度的干扰），得到有效内部区域 $R_{\text{inner}}$：
   $$\text{SumGrad} = S_{\text{grad}}(x_2, y_2) - S_{\text{grad}}(x_1, y_2) - S_{\text{grad}}(x_2, y_1) + S_{\text{grad}}(x_1, y_1)$$
   $$\text{MeanGrad} = \frac{\text{SumGrad}}{\text{Area}(R_{\text{inner}})}$$
4. **显著度权重增益 (Saliency Gain)**：
   空白车门玻璃由于内部平坦，其 $\text{MeanGrad} < 3.0$；而包含汉字、英文字母及地铁图标的高密度标牌，其 $\text{MeanGrad} \in [15.0, 50.0]$。
   $$W_{\text{saliency}} = 1.0 + 3.0 \times \text{clamp}\left(\frac{\text{MeanGrad} - 3.5}{16.0}, 0.0, 1.0\right)$$
   使包含实质文字内容的文档获得高达 $1.0\times \sim 4.0\times$ 的综合提权。

### 2.3 触控意图加权 (Touch Bonus)
当用户在屏幕上点击目标标牌时，传入归一化坐标 $(u, v)$：
$$W_{\text{touch}} = \begin{cases} 3.0, & \text{if } (u \cdot W, v \cdot H) \in \text{Polygon} \\ 1.0, & \text{otherwise} \end{cases}$$

---

## 3. 综合多目标打分函数 (Composite Scoring Function)

最终候选四边形评分公式：
$$\text{Score} = \text{Rectangularity} \times \text{CenterBias} \times (\sqrt{\text{AreaRatio}} + 0.18) \times W_{\text{saliency}} \times W_{\text{touch}}$$
算法按 $\text{Score}$ 降序排列并选取最优四边形输出，彻底兼顾了常规 A4 书籍纸张与车站条形信息牌的鲁棒检测。
