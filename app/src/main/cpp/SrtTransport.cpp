#include "SrtTransport.h"
#include <android/log.h>
#include <arpa/inet.h>
#include <netdb.h>
#include <sys/socket.h>
#include <string.h>
#include <thread>
#include <chrono>

#define TAG "SrtTransport"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

SrtTransport::SrtTransport() {
    srt_startup();
}

SrtTransport::~SrtTransport() {
    release();
    srt_cleanup();
}

bool SrtTransport::init(const std::string& ip, int port, const std::string& streamId) {
    // Store for reconnection
    ip_ = ip;
    port_ = port;
    streamId_ = streamId;
    
    return connect();
}

bool SrtTransport::connect() {
    if (connected_) return true;
    
    LOGI("Attempting SRT connection to %s:%d", ip_.c_str(), port_);

    socket_ = srt_create_socket();
    if (socket_ == SRT_INVALID_SOCK) {
        LOGE("Failed to create SRT socket");
        return false;
    }

    // Set sender options
    bool tr = true;
    srt_setsockopt(socket_, 0, SRTO_SENDER, &tr, sizeof tr);
    
    // Set Stream ID (Required for MediaMTX)
    if (!streamId_.empty()) {
        std::string sid = "publish:" + streamId_;
        srt_setsockopt(socket_, 0, SRTO_STREAMID, sid.c_str(), sid.size());
        LOGI("Set StreamID: %s", sid.c_str());
    }
    
    // HIGH LATENCY for sea/unstable network (15 seconds buffer)
    // This allows the receiver to buffer more data before playback
    int latency = 15000; // 15 seconds
    srt_setsockopt(socket_, 0, SRTO_LATENCY, &latency, sizeof latency);
    LOGI("Set Latency: %d ms (for sea stability)", latency);

    // Live mode
    int transtype = SRTT_LIVE;
    srt_setsockopt(socket_, 0, SRTO_TRANSTYPE, &transtype, sizeof transtype);
    
    // Connection timeout (10 seconds for slow networks)
    int conntime = 10000;
    srt_setsockopt(socket_, 0, SRTO_CONNTIMEO, &conntime, sizeof conntime);

    // Large flight window for high latency
    int fc = 32000;
    srt_setsockopt(socket_, 0, SRTO_FC, &fc, sizeof fc);
    
    // Large sender buffer (50MB for 15 seconds of video)
    int bufSize = 50000000;
    srt_setsockopt(socket_, 0, SRTO_SNDBUF, &bufSize, sizeof bufSize);
    
    // Enable peer idle timeout (30 seconds)
    int peerIdleTimeout = 30000;
    srt_setsockopt(socket_, 0, SRTO_PEERIDLETIMEO, &peerIdleTimeout, sizeof peerIdleTimeout);

    sockaddr_in sa;
    sa.sin_family = AF_INET;
    sa.sin_port = htons(port_);
    if (inet_pton(AF_INET, ip_.c_str(), &sa.sin_addr) != 1) {
        LOGE("Invalid IP address");
        srt_close(socket_);
        socket_ = SRT_INVALID_SOCK;
        return false;
    }

    LOGI("Connecting to SRT %s:%d", ip_.c_str(), port_);
    int res = srt_connect(socket_, (sockaddr*)&sa, sizeof sa);
    if (res == SRT_ERROR) {
        LOGE("SRT connect failed: %s", srt_getlasterror_str());
        srt_close(socket_);
        socket_ = SRT_INVALID_SOCK;
        return false;
    }

    connected_ = true;
    reconnectAttempts_ = 0;
    LOGI("SRT Connected successfully!");
    return true;
}

void SrtTransport::send(const uint8_t* data, int len) {
    if (!connected_ || socket_ == SRT_INVALID_SOCK) {
        // Try to reconnect if not connected
        if (!reconnecting_) {
            tryReconnect();
        }
        return;
    }

    int res = srt_sendmsg(socket_, (const char*)data, len, -1, 0);
    if (res == SRT_ERROR) {
        int errCode = srt_getlasterror(nullptr);
        LOGE("SRT send failed: %s (code: %d)", srt_getlasterror_str(), errCode);
        
        // Connection lost - trigger reconnection
        if (errCode == SRT_ECONNLOST || errCode == SRT_ENOCONN || errCode == SRT_EINVSOCK) {
            LOGW("Connection lost, will attempt reconnect...");
            connected_ = false;
            if (socket_ != SRT_INVALID_SOCK) {
                srt_close(socket_);
                socket_ = SRT_INVALID_SOCK;
            }
            tryReconnect();
        }
    }
}

void SrtTransport::tryReconnect() {
    if (reconnecting_) return;
    reconnecting_ = true;
    
    // Reconnect in a separate thread to not block video encoding
    std::thread([this]() {
        while (!connected_ && reconnectAttempts_ < MAX_RECONNECT_ATTEMPTS) {
            reconnectAttempts_++;
            LOGW("Reconnection attempt %d/%d...", reconnectAttempts_, MAX_RECONNECT_ATTEMPTS);
            
            // Wait before retry (exponential backoff: 1s, 2s, 4s, 8s, 16s)
            int waitMs = std::min(1000 * (1 << (reconnectAttempts_ - 1)), 16000);
            std::this_thread::sleep_for(std::chrono::milliseconds(waitMs));
            
            if (connect()) {
                LOGI("Reconnection successful after %d attempts", reconnectAttempts_);
                break;
            }
        }
        
        if (!connected_) {
            LOGE("Failed to reconnect after %d attempts", MAX_RECONNECT_ATTEMPTS);
        }
        
        reconnecting_ = false;
    }).detach();
}

void SrtTransport::release() {
    connected_ = false;
    if (socket_ != SRT_INVALID_SOCK) {
        srt_close(socket_);
        socket_ = SRT_INVALID_SOCK;
    }
}

