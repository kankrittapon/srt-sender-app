#pragma once

#include <vector>
#include <cstdint>
#include <functional>

class MpegTsMuxer {
public:
    using OutputCallback = std::function<void(const uint8_t*, size_t)>;

    MpegTsMuxer(OutputCallback callback);
    ~MpegTsMuxer();

    // Reset continuity counters and other state
    void reset();

    // Input H.264 NALUs (annex B format with start codes 00 00 00 01 or 00 00 01)
    void encode(const uint8_t* data, size_t size, uint64_t pts_ns);

private:
    OutputCallback callback_;
    uint8_t continuity_counter_pat_ = 0;
    uint8_t continuity_counter_pmt_ = 0;
    uint8_t continuity_counter_video_ = 0;
    
    // Buffering for SRT (7 * 188 = 1316 bytes)
    static const size_t BUFFER_SIZE = 1316;
    uint8_t output_buffer_[BUFFER_SIZE];
    size_t buffer_offset_ = 0;
    
    // Helper to buffer and send
    void bufferPacket(const uint8_t* packet);
    
    // Force flush any remaining buffered data
    void flushBuffer();
    
    // Write PAT and PMT tables if needed (usually every 100ms or so, but for simplicity every frame)
    void writePatPmt();
    
    // Encapsulate payload into TS packets
    void writePesPacket(const uint8_t* payload, size_t size, uint64_t pts_90khz, bool keyframe);
    
    // Helper to write a single TS packet
    void writeTsPacket(uint16_t pid, uint8_t* payload, size_t payload_size, uint8_t& continuity_counter, bool unit_start);
};
