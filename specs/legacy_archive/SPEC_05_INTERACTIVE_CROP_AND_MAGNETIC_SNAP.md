# 技术规格书 05：基于四线拦截模型与迟滞锁定的全维度磁吸系统规格书

> **文档说明**：陈述解析几何模型、二维射影几何直线交点公式、梯度场吸附数学模型与交互状态机契约。

---

## 1. 四线拦截几何模型 (Four-Line Interception Geometry Model)

在文档裁剪中，任意凸四边形边界由**四条无限延伸的平面直线**定义：
* $L_0$：顶边直线 (Top Line)
* $L_1$：右边直线 (Right Line)
* $L_2$：底边直线 (Bottom Line)
* $L_3$：左边直线 (Left Line)

四个角点严格由相邻直线的交点唯一确定：
* $P_0 = L_3 \cap L_0$ (Top-Left)
* $P_1 = L_0 \cap L_1$ (Top-Right)
* $P_2 = L_1 \cap L_2$ (Bottom-Right)
* $P_3 = L_2 \cap L_3$ (Bottom-Left)

### 1.1 直线方程表示 (Line Equation Formulation)
通过点 $A(x_1, y_1)$ 与 $B(x_2, y_2)$ 的直线标准代数方程定义为：
$$A x + B y + C = 0$$
其中：
$$A = y_2 - y_1, \quad B = x_1 - x_2, \quad C = x_2 y_1 - x_1 y_2$$

单位法向量定义为：
$$\vec{n} = \left(\frac{A}{\sqrt{A^2 + B^2}}, \frac{B}{\sqrt{A^2 + B^2}}\right)$$

### 1.2 两线交点公式 (Two-Line Intersection)
给定两非平行直线 $L_a: A_1 x + B_1 y + C_1 = 0$ 与 $L_b: A_2 x + B_2 y + C_2 = 0$，行列式为：
$$D = A_1 B_2 - A_2 B_1$$
当 $|D| > 10^{-6}$ 时，其唯一交点坐标为：
$$x_{int} = \frac{B_1 C_2 - B_2 C_1}{D}, \quad y_{int} = \frac{A_2 C_1 - A_1 C_2}{D}$$

---

## 2. 交互手柄判定与行为契约 (Handle Interaction Contract)

### 2.1 手柄就近竞争判定协议 (Handle Distance-Competition Protocol)
为彻底杜绝“调整边缘线时误触角点”的问题，严禁采用“优先判定角点且提前返回”的阶梯逻辑。必须采用**全局就近竞争**：
1. **热区半径标准**：
   * 角点热区半径 $R_{corner} = 38\text{ dp}$（收缩至合适尺寸，严禁过度外溢）。
   * 边中点热区半径 $R_{midpoint} = 44\text{ dp}$。
   * 边线段线身热区半径 $R_{edge} = 32\text{ dp}$。
2. **就近判决法则 (Nearest-Handle Rule)**：
   * 计算触摸点到所有 4 个角点的最小距离 $d_{c\_min}$ 及对应角点索引 $i_c$。
   * 计算触摸点到所有 4 个边中点的最小距离 $d_{m\_min}$ 及对应边索引 $i_m$。
   * 计算触摸点到所有 4 条边线段的垂直投影距离 $d_{e\_min}$ 及对应边索引 $i_e$。
   * **判决优先级**：
     * 若 $d_{m\_min} \le R_{midpoint}$ 且 $d_{m\_min} \le d_{c\_min} + 8\text{ dp}$，判定为**拖拽边中点 $i_m$**（优先保护边中点意图）；
     * 否则，若 $d_{c\_min} \le R_{corner}$，判定为**拖拽角点 $i_c$**；
     * 否则，若 $d_{e\_min} \le R_{edge}$，判定为**拖拽边线段 $i_e$**；
     * 若均未命中，判定为**点击背景/文档区域**。

### 2.2 边缘中点拖拽行为
当用户拖拽边 $i$ 的中点手柄时（位移矢量为 $\vec{d} = (\Delta x, \Delta y)$）：
1. 仅平移直线 $L_i$ 本身，沿其法向量方向投影位移：
   $$\Delta d_\perp = \vec{d} \cdot \vec{n}_i$$
   $$C_i' = C_i - \Delta d_\perp \sqrt{A_i^2 + B_i^2}$$
2. 相邻两条直线 $L_{(i-1)\%4}$ 与 $L_{(i+1)\%4}$ 保持绝对静止。
3. 角点自动沿相邻直线滑动拦截：
   * $P_i' = L_{(i-1)\%4} \cap L_i'$
   * $P_{(i+1)\%4}' = L_i' \cap L_{(i+1)\%4}$
   * 其余角点保持不变。

---

## 3. 磁吸判决管线与防抽搐迟滞锁定 (Anti-Jitter Hysteresis Snapping)

### 3.1 真实图像内容边缘梯度吸附 (Content-Aware Edge & Corner Snapping)
1. **边缘场提取**：采用 CLAHE（clipLimit=2.0）搭配敏感固定双阈值（$T_{low}=25.0, T_{high}=70.0$）生成 `EdgeMap`。
2. **直线法向梯度累加吸附**：
   沿移动直线 $L_i'$ 均匀采样 $N = 25$ 个采样点 $S_k$。在法向偏移 $\delta \in [-\tau_{snap}, +\tau_{snap}]$ 区间内统计边缘得分：
   $$\text{Score}(\delta) = \sum_{k=1}^N \text{EdgeMap}(S_k + \delta \cdot \vec{n}_i)$$
   有效峰值门限为 $\text{minThreshold} = 0.10 \times N \times 255$。

### 3.2 迟滞吸附状态机 (Bi-directional Hysteresis Latching - 消除抽搐核心)
高频抽搐/抖动（Jitter）源于单一门限下的临界状态往复震荡。必须引入物理迟滞（Hysteresis）：
* **吸入门限 (Snap-In Threshold)**：$\tau_{in} = 20\text{ dp}/s$。当手指拖动直线到物理边缘距离 $\le \tau_{in}$ 时，进入**锁定吸附状态 (LOCKED)**。
* **脱离门限 (Break-Away Threshold)**：$\tau_{out} = 34\text{ dp}/s$。一旦进入锁定吸附状态，直线**死死固定在物理边缘上**，手指在小幅震颤（$<\tau_{out}$）时直线保持不动，直到手指用力拉开超过 $\tau_{out}$ 时才脱离吸附。
* **振动降噪**：线性马达仅在从“未吸附”进入“锁定吸附”的瞬间触发单次轻触微振，锁定维持期间严禁重复触发振动。

### 3.3 角点吸附迟滞 (Corner Snap Latching)
角点进入物理强边缘吸附后，记录吸附锚点坐标 $(x_{lock}, y_{lock})$。在手指位移小于 $\tau_{out}$ 时保持锁定，超过 $\tau_{out}$ 后平滑释放。

### 3.4 其它辅助吸附与点击吸附
* **画布外边界吸附**：靠近图片边界时吸附在 $0, W, H$；
* **正交拉直**：仅当边缘与坐标轴倾角 $< 3^\circ$ 时辅助拉平；
* **点击区域秒吸附 (Tap-to-Snap)**：点击画布内部非手柄区域时，自动探测包含该点的闭合多边形并对齐。

---

## 4. 应用包名契约 (Package Configuration Contract)
* `applicationId` 必须设为 `com.open.scan`。
