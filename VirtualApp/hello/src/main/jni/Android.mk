LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE := vaxprobe
LOCAL_SRC_FILES := probe.c
LOCAL_LDLIBS := -ldl
include $(BUILD_SHARED_LIBRARY)
