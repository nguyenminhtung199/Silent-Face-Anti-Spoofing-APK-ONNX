//
// Created by yuanhao on 20-6-12.
//

#ifndef LIVEBODYEXAMPLE_LIVE_H
#define LIVEBODYEXAMPLE_LIVE_H

#include <opencv2/core/mat.hpp>
#include <onnxruntime/core/session/onnxruntime_cxx_api.h>
#include <android/asset_manager.h>
#include "../definition.h"
#include <vector>
#include <string>

class Live {
public:
    Live();

    ~Live();

    int LoadModel(AAssetManager *assetManager, std::vector<ModelConfig> &configs);

    float Detect(cv::Mat &src, FaceBox &box);

private:
    cv::Rect CalculateBox(FaceBox &box, int w, int h, ModelConfig &config);


private:
    Ort::Env* env;
    Ort::SessionOptions session_options;
    std::vector<Ort::Session*> sessions_;
    std::vector<ModelConfig> configs_;
    std::vector<const char*> input_names_;
    std::vector<const char*> output_names_;
    int model_num_;
    int thread_num_;
};

#endif //LIVEBODYEXAMPLE_LIVE_H
