#pragma once

#include <string>
#include <vector>
#include <atomic>
#include <srt.h>

class SrtTransport {
public:
    SrtTransport();
    ~SrtTransport();

    bool init(const std::string& ip, int port, const std::string& streamId);
    void send(const uint8_t* data, int len);
    void release();

private:
    bool connect();
    void tryReconnect();
    
    SRTSOCKET socket_ = SRT_INVALID_SOCK;
    bool connected_ = false;
    
    // Connection parameters (for reconnection)
    std::string ip_;
    int port_ = 0;
    std::string streamId_;
    
    // Reconnection state
    std::atomic<bool> reconnecting_{false};
    int reconnectAttempts_ = 0;
    static const int MAX_RECONNECT_ATTEMPTS = 10;
};

