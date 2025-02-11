//
// Created by yuanhao on 20-6-12.
//

#include <opencv2/imgproc.hpp>
#include <opencv2/imgcodecs.hpp>
#include <onnxruntime/core/session/onnxruntime_cxx_api.h>
#include "live.h"
#include "../android_log.h"

Live::Live() {
    thread_num_ = 3;
    LOG_DEBUG("Live constructor called. thread_num_: %d", thread_num_);
    // Initialize ONNX Runtime environment
    env = new Ort::Env(ORT_LOGGING_LEVEL_WARNING, "Live");
    session_options.SetIntraOpNumThreads(thread_num_);
    session_options.SetGraphOptimizationLevel(GraphOptimizationLevel::ORT_ENABLE_EXTENDED);

    LOG_DEBUG("ONNX Runtime environment initialized");
}

Live::~Live() {
    LOG_DEBUG("Live destructor called");
    delete env;

    for (int i = 0; i < sessions_.size(); ++i) {
        delete sessions_[i];
    }
    sessions_.clear();
    LOG_DEBUG("All sessions deleted and cleared");
}

int Live::LoadModel(AAssetManager *assetManager, std::vector<ModelConfig> &configs) {
    LOG_DEBUG("LoadModel called");
    configs_ = configs;
    model_num_ = configs_.size();

    for (int i = 0; i < model_num_; ++i) {
        std::string model_path = "live/" + configs_[i].name + ".onnx";
        input_names_.emplace_back("input");
        output_names_.emplace_back("output");
        // Read model file from asset manager
        AAsset* asset = AAssetManager_open(assetManager, model_path.c_str(), AASSET_MODE_BUFFER);
        if (!asset) {
            LOG_WARN("Failed to open model file: %s", model_path.c_str());
            return -1;
        }
        size_t model_size = AAsset_getLength(asset);
        std::vector<uint8_t> model_data(model_size);
        AAsset_read(asset, model_data.data(), model_size);
        AAsset_close(asset);
        LOG_DEBUG("Model size: %zu bytes", model_size);
        try {
            Ort::Session* session = new Ort::Session(*env, model_data.data(), model_size, session_options);
            sessions_.emplace_back(session);
            LOG_DEBUG("Model loaded successfully");
        } catch (const Ort::Exception& e) {
            LOG_DEBUG("Failed to create ONNX session: %s", e.what());
            return -1;
        }

    }

    return 0;
}

float Live::Detect(cv::Mat &src, FaceBox &box) {
    LOG_DEBUG("Detect called. Input image size: %dx%d", src.cols, src.rows);
    float confidence = 0.f;
    for (int i = 0; i < model_num_; i++) {
        cv::Mat roi;
        if(configs_[i].org_resize) {
            cv::resize(src, roi, cv::Size(configs_[i].width, configs_[i].height));
        } else {
            cv::Rect rect = CalculateBox(box, src.cols, src.rows, configs_[i]);
            cv::resize(src(rect), roi, cv::Size(configs_[i].width, configs_[i].height));
//            LOG_DEBUG("Calculated Rect: x = {%d}, y = {%d}, width = {%d}, height = {%d}",
//                      rect.x, rect.y, rect.width, rect.height);
        }

//        for (int y = 0; y < roi.rows; y++) {
//            for (int x = 0; x < roi.cols; x++) {
//                cv::Vec3b pixel = roi.at<cv::Vec3b>(y, x); // Chỉ dùng khi roi là ảnh màu 3 kênh
//                LOG_DEBUG("Pixel at (%d, %d): [B: %d, G: %d, R: %d]", x, y, pixel[0], pixel[1], pixel[2]);
//            }
//        }

        cv::cvtColor(roi, roi, cv::COLOR_BGR2RGB);
        cv::Mat mat_ref;
        if (roi.type() != CV_32FC(roi.channels())) roi.convertTo(mat_ref, CV_32FC(roi.channels()));
        else mat_ref = roi;

        Ort::MemoryInfo memory_info{Ort::MemoryInfo::CreateCpu(OrtArenaAllocator, OrtMemTypeDefault)};

        // Convert Mat to float tensor
        std::vector<float> input_tensor_values(mat_ref.channels() * mat_ref.rows * mat_ref.cols);
        std::vector<cv::Mat> mat_channels;
        cv::split(mat_ref, mat_channels);
        for (unsigned int j = 0; j < mat_ref.channels(); ++j)
        {
            std::memcpy(input_tensor_values.data() + j * (mat_ref.rows * mat_ref.cols), mat_channels.at(j).data,mat_ref.rows * mat_ref.cols * sizeof(float));
        }

        std::vector<int64_t> input_shape = {1, mat_ref.channels(), mat_ref.rows, mat_ref.cols};
        Ort::Value input_tensor = Ort::Value::CreateTensor<float>(memory_info, input_tensor_values.data(), input_tensor_values.size(), input_shape.data(), input_shape.size());
        //        float* tensor_data = input_tensor.GetTensorMutableData<float>();
        //        size_t tensor_size = 1;
        //        for (auto dim : input_shape) {
        //            tensor_size *= dim;
        //        }
        //        for (size_t i = 0; i < tensor_size; ++i) {
        //            LOG_DEBUG("input_tensor[%zu] = %f", i, tensor_data[i]);
        //        }
        //       LOG_DEBUG("Input tensor shape: %lldx%lldx%lldx%lld", input_shape[0], input_shape[1], input_shape[2], input_shape[3]);
        // Run the model
        auto output_tensors = sessions_[i]->Run(Ort::RunOptions{nullptr}, &input_names_[i], &input_tensor, 1, &output_names_[i], 1);
        Ort::Value &feat = output_tensors.at(0);
        float *floatarr = feat.GetTensorMutableData<float>();
        size_t arr_size = output_tensors.at(0).GetTensorTypeAndShapeInfo().GetElementCount();  // Lấy kích thước của mảng

        LOG_DEBUG("model[%d]: floatarr[0] = %f",i , floatarr[0]);

//        for (size_t k = 0; k < 1; ++k) {
//            LOG_DEBUG("model[%d]: floatarr[%zu] = %f",i , k, floatarr[k]);
//        }
        confidence += floatarr[0];
    }
    confidence /= model_num_;

    box.confidence = confidence;
    return confidence;
}

cv::Rect Live::CalculateBox(FaceBox &box, int w, int h, ModelConfig &config) {
    int x = static_cast<int>(box.x1);
    int y = static_cast<int>(box.y1);
    int box_width = static_cast<int>(box.x2 - box.x1 + 1);
    int box_height = static_cast<int>(box.y2 - box.y1 + 1);

    int shift_x = static_cast<int>(box_width * config.shift_x);
    int shift_y = static_cast<int>(box_height * config.shift_y);

    float scale = std::min(
            config.scale,
            std::min((w - 1) / (float) box_width, (h - 1) / (float) box_height)
    );

    int box_center_x = box_width / 2 + x;
    int box_center_y = box_height / 2 + y;

    int new_width = static_cast<int>(box_width * scale);
    int new_height = static_cast<int>(box_height * scale);

    int left_top_x = box_center_x - new_width / 2 + shift_x;
    int left_top_y = box_center_y - new_height / 2 + shift_y;
    int right_bottom_x = box_center_x + new_width / 2 + shift_x;
    int right_bottom_y = box_center_y + new_height / 2 + shift_y;

    if (left_top_x < 0) {
        right_bottom_x -= left_top_x;
        left_top_x = 0;
    }

    if (left_top_y < 0) {
        right_bottom_y -= left_top_y;
        left_top_y = 0;
    }

    if (right_bottom_x >= w) {
        int s = right_bottom_x - w + 1;
        left_top_x -= s;
        right_bottom_x -= s;
    }

    if (right_bottom_y >= h) {
        int s = right_bottom_y - h + 1;
        left_top_y -= s;
        right_bottom_y -= s;
    }

    return cv::Rect(left_top_x, left_top_y, new_width, new_height);
}