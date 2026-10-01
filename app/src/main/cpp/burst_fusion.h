#ifndef BURST_FUSION_H
#define BURST_FUSION_H

#include <opencv2/opencv.hpp>
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

    // 暗光极高光质心与微细节配准 (Highlight Centroid & Correlation Alignment for dark scenes)
    bool alignHighlightFrame(const cv::Mat& srcShort, const cv::Mat& refBase, cv::Mat& outWarped, cv::Mat& outH);

private:
    // 计算欠曝帧到基准帧在高光过渡区 (Y0 in [210, 240]) 的平滑亮度自适应增益比
    float estimateHighlightAdaptationGain(const cv::Mat& bgrBase, const cv::Mat& bgrCand, const cv::Mat& validMask);
};

#endif // BURST_FUSION_H
