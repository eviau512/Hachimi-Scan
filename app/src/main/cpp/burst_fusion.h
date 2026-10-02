#ifndef BURST_FUSION_H
#define BURST_FUSION_H

#include <opencv2/opencv.hpp>
#include <opencv2/photo.hpp>
#include <vector>

class BurstFusionEngine {
public:
    // 真正过曝饱和嫁接与高光微细节保真 HDR 融合 / 1分4 50MP 亚像素超分 (SPEC_12 & 50MP Super-Res)
    // @param burstFrames: 连拍输入序列 (BGR格式, burstFrames[0] 为基准帧 EV=0)
    // @param removeGlare: 保留兼容接口标志位
    // @param isScreenMode: 保留兼容接口标志位
    // @param superResolution: 是否开启 1分4 (12.5MP -> 50MP) 多帧亚像素超分重建
    // @return: 融合后的超清晰、高动态或 50MP 超分图像
    cv::Mat fuseBurstFrames(const std::vector<cv::Mat>& burstFrames, bool removeGlare = false, bool isScreenMode = false, bool superResolution = false);

    // 第一级：全局单应性粗配准 (ORB + RANSAC Homography)
    bool alignFrameHomography(const cv::Mat& src, const cv::Mat& ref, cv::Mat& outWarped, cv::Mat& outH);

    // 基于 Greg Ward 经典中值阈值位图的多曝光对齐 (AlignMTB)
    // 专为跨大曝光级差的 HDR 连拍设计，免疫大面积过曝与纯黑，杜绝重影和发光环错位
    bool alignFrameMTB(const cv::Mat& src, const cv::Mat& ref, cv::Mat& outWarped, cv::Mat& outH);

    // 梯度域相位相关配准 (Gradient-Domain Phase Correlation)
    // 采用对数梯度幅值 Log(1 + |grad|) + Hanning 窗，免疫曝光级差剧变，实现亚像素刚体位移计算
    bool alignGradientPhaseCorrelation(const cv::Mat& src, const cv::Mat& ref, cv::Mat& outWarped, cv::Mat& outH);

    // 高光多候选 ROI 归一化互相关模板精配准 (Multi-ROI NCC Template Match)
    // 专门针对夜景灯芯、环形灯管、发光字，通过局部互相关峰值与抛物线拟合达到 0.05 像素精度
    bool alignHighlightTemplate(const cv::Mat& srcShort, const cv::Mat& refBase, cv::Mat& outWarped, cv::Mat& outH);

    // 暗光高动态场景极高光结构配准 (Highlight Structure & Multi-Algorithm Alignment)
    // 优先 CLAHE ORB -> NCC Template -> 对数梯度相位相关 -> 恒等矩阵保底，彻底杜绝桌面反光拉偏和发光环错位重影
    bool alignHighlightFrame(const cv::Mat& srcShort, const cv::Mat& refBase, cv::Mat& outWarped, cv::Mat& outH);

private:
    float estimateHighlightAdaptationGain(const cv::Mat& bgrBase, const cv::Mat& bgrCand, const cv::Mat& validMask);
};

#endif // BURST_FUSION_H
