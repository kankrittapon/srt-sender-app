#include "MpegTsMuxer.h"
#include <cstring>
#include <cstdio>

// Constants
static const uint16_t PID_PAT = 0x0000;
static const uint16_t PID_PMT = 0x1000;
static const uint16_t PID_VIDEO = 0x0100;
static const uint8_t STREAM_ID_VIDEO = 0xE0;
static const size_t TS_PACKET_SIZE = 188;

MpegTsMuxer::MpegTsMuxer(OutputCallback callback) : callback_(callback) {}

MpegTsMuxer::~MpegTsMuxer() {}

void MpegTsMuxer::reset() {
    continuity_counter_pat_ = 0;
    continuity_counter_pmt_ = 0;
    continuity_counter_video_ = 0;
    buffer_offset_ = 0;
}

void MpegTsMuxer::bufferPacket(const uint8_t* packet) {
    memcpy(output_buffer_ + buffer_offset_, packet, TS_PACKET_SIZE);
    buffer_offset_ += TS_PACKET_SIZE;
    
    if (buffer_offset_ >= BUFFER_SIZE) {
        callback_(output_buffer_, BUFFER_SIZE);
        buffer_offset_ = 0;
    }
}

void MpegTsMuxer::flushBuffer() {
    if (buffer_offset_ > 0) {
        // Send whatever is in the buffer, even if not full.
        // MediaMTX should be able to handle partial payloads.
        callback_(output_buffer_, buffer_offset_);
        buffer_offset_ = 0;
    }
}

void MpegTsMuxer::encode(const uint8_t* data, size_t size, uint64_t pts_ns) {
    // Write PAT and PMT before every keyframe or periodically. 
    // Ideally check if keyframe, but simpler to write often or check NAL type.
    // For now, let's write it every time to ensure quick start, minimal overhead.
    writePatPmt();
    
    // Flush immediately after PAT/PMT to ensure MediaMTX detects the stream ASAP.
    flushBuffer();

    uint64_t pts_90khz = pts_ns / 11111; // 10^9 / 90000 = 11111.111
    
    // Simple NALU parsing to detect keyframe isn't robust here without full parse,
    // assuming input is a full access unit.
    // Usually standard MediaCodec output contains IDR + SPS + PPS in start.
    
    // Improved NALU scanning to find IDR (type 5) anywhere in the buffer
    // This handles the case where SPS(7)/PPS(8) come before IDR in the same buffer.
    bool keyframe = false;
    for (size_t i = 0; i < size - 4; i++) {
        if (data[i] == 0x00 && data[i+1] == 0x00) {
            if (data[i+2] == 0x01) {
                int type = data[i+3] & 0x1F;
                if (type == 5) { // IDR
                    keyframe = true;
                    break;
                }
            } else if (data[i+2] == 0x00 && data[i+3] == 0x01) {
                if (i + 4 < size) {
                    int type = data[i+4] & 0x1F;
                    if (type == 5) {
                        keyframe = true;
                        break;
                    }
                }
            }
        }
    }

    writePesPacket(data, size, pts_90khz, keyframe);
}

// CRC32 implementation for MPEG-TS
static uint32_t calculate_crc32(const uint8_t *data, size_t size) {
    uint32_t crc = 0xFFFFFFFF;
    for (size_t i = 0; i < size; i++) {
        uint8_t byte = data[i];
        for (int j = 0; j < 8; j++) {
            bool bit = (byte >> (7 - j)) & 1;
            bool c15 = (crc >> 31) & 1;
            crc <<= 1;
            if (bit ^ c15) {
                crc ^= 0x04C11DB7;
            }
        }
    }
    return crc;
}

void MpegTsMuxer::writePatPmt() {
    uint8_t packet[TS_PACKET_SIZE];
    memset(packet, 0xFF, TS_PACKET_SIZE);

    // --- PAT ---
    // Section data starts after pointer field
    // Payload: [Pointer(0)] [TableID(0)] ... [CRC(4)]
    // CRC is calculated on: TableID ... Last byte of data (before CRC)
    
    uint8_t pat_section[] = {
        0x00, // Table ID (PAT)
        0xB0, 0x0D, // Section Length (13)
        0x00, 0x01, // Transport Stream ID
        0xC1, // Version (0), Current/Next (1)
        0x00, // Section number
        0x00, // Last section number
        // Program 1 -> PID_PMT (0x1000)
        0x00, 0x01, // Program Number 1
        0xF0, 0x00  // PID (0x1000) reserved(3)|pid(13) -> 111|1000000000000 -> 0x1F... wait.
                    // PID_PMT is 0x1000 (4096).
                    // 0xE000 | 0x1000 = 0xF000. Correct.
    };
    
    // Calculate CRC
    uint32_t pat_crc = calculate_crc32(pat_section, sizeof(pat_section));
    
    uint8_t* p = packet;
    *p++ = 0x47;
    *p++ = 0x40 | (0x00 & 0x1F); // PID 0
    *p++ = 0x00;
    *p++ = 0x10 | (continuity_counter_pat_ & 0x0F);
    continuity_counter_pat_++;
    
    *p++ = 0x00; // Pointer field
    memcpy(p, pat_section, sizeof(pat_section));
    p += sizeof(pat_section);
    // Write CRC (Big Endian)
    *p++ = (pat_crc >> 24) & 0xFF;
    *p++ = (pat_crc >> 16) & 0xFF;
    *p++ = (pat_crc >> 8) & 0xFF;
    *p++ = pat_crc & 0xFF;
    
    bufferPacket(packet);

    // --- PMT ---
    memset(packet, 0xFF, TS_PACKET_SIZE);
    
    // PMT Section
    uint8_t pmt_section[] = {
        0x02, // Table ID (PMT)
        0xB0, 0x12, // Section Length (18)
        0x00, 0x01, // Program Number
        0xC1, // Version, Current/Next
        0x00, 0x00, // Section/Last
        0xE1, 0x00, // PCR PID (PID_VIDEO for simplistic approach) -> 0xE000|0x0100 = 0xE100
        0xF0, 0x00, // Program Info Length (0)
        // Stream 1: H.264
        0x1B, // Stream Type (H.264)
        0xE1, 0x00, // PID (0x0100) -> 0xE100
        0xF0, 0x00  // ES Info Length
    };

    // Calculate CRC
    uint32_t pmt_crc = calculate_crc32(pmt_section, sizeof(pmt_section));

    p = packet;
    *p++ = 0x47;
    *p++ = 0x40 | ((PID_PMT >> 8) & 0x1F);
    *p++ = PID_PMT & 0xFF;
    *p++ = 0x10 | (continuity_counter_pmt_ & 0x0F);
    continuity_counter_pmt_++;
    
    *p++ = 0x00; // Pointer field
    memcpy(p, pmt_section, sizeof(pmt_section));
    p += sizeof(pmt_section);
    // Write CRC
    *p++ = (pmt_crc >> 24) & 0xFF;
    *p++ = (pmt_crc >> 16) & 0xFF;
    *p++ = (pmt_crc >> 8) & 0xFF;
    *p++ = pmt_crc & 0xFF;

    bufferPacket(packet);
}

void MpegTsMuxer::writePesPacket(const uint8_t* payload, size_t size, uint64_t pts_90khz, bool keyframe) {
    // Max payload per packet approx 184 bytes.
    
    // PES Header:
    // Packet start code prefix (24): 00 00 01
    // Stream ID (8): 
    // Packet Length (16): 0 for video usually OK (unbounded)
    // Flags (16): ...
    // Header Length (8): ...
    // PTS (40): ...

    uint8_t pes_header[19]; // Minimal header with PTS
    pes_header[0] = 0x00; pes_header[1] = 0x00; pes_header[2] = 0x01;
    pes_header[3] = STREAM_ID_VIDEO;
    pes_header[4] = 0x00; pes_header[5] = 0x00; // Length
    
    pes_header[6] = 0x80; // Marker bits + Scrambling control + Priority + Data alignment... 0x80 (10000000)
    pes_header[7] = 0x80; // PTS only flag (0x80). DTS (0x40) Not dealing with B-frames yet?
    pes_header[8] = 0x05; // Header data length (5 bytes for PTS)
    
    // PPS encoding
    // 0010 (4) | PTS[32..30] (3) | marker (1)
    pes_header[9] = 0x21 | ((pts_90khz >> 29) & 0x0E); 
    // PTS[29..15] (15) | marker (1)
    uint16_t mid = (pts_90khz >> 15) & 0x7FFF;
    pes_header[10] = (mid >> 7) & 0xFF;
    pes_header[11] = ((mid << 1) & 0xFE) | 0x01;
    // PTS[14..0] (15) | marker (1)
    uint16_t low = pts_90khz & 0x7FFF;
    pes_header[12] = (low >> 7) & 0xFF;
    pes_header[13] = ((low << 1) & 0xFE) | 0x01;

    size_t pes_header_len = 14; 
    
    // We need to send Adaption Field on first packet to carry PCR?
    // Usually PCR is needed. Let's put PCR same as PTS for now.
    
    size_t remaining_size = size;
    const uint8_t* current_payload = payload;
    bool first_packet = true;

    while (remaining_size > 0 || first_packet) {
        uint8_t packet[TS_PACKET_SIZE];
        memset(packet, 0xFF, TS_PACKET_SIZE); // Fill defined stuffing
        
        uint8_t* p = packet;
        *p++ = 0x47; // Sync
        
        uint16_t pid_high = ((PID_VIDEO >> 8) & 0x1F);
        if (first_packet) pid_high |= 0x40; // Payload Unit Start Indicator
        *p++ = pid_high;
        *p++ = PID_VIDEO & 0xFF;
        
        // Adaptation field existence?
        // We need adaptation field if:
        // 1. We want to send PCR (first packet)
        // 2. We need to pad the packet (last packet)
        
        bool has_pcr = first_packet; 
        
        size_t available_payload_space = TS_PACKET_SIZE - 4; // Header size
        size_t needed_stuffing = 0;
        size_t adaptation_field_len = 0;
        
        if (has_pcr) {
            adaptation_field_len = 7; // Length(1) + Flags(1) + PCR(5) ? PCR is 6 bytes (33 base + 6 ext + res) -> 48 bits = 6 bytes.
            // Wait, PCR is 6 bytes program_clock_reference_base(33) + reserved(6) + program_clock_reference_extension(9).
            // Header: Length(1) + Flags(1). Total 2 + 6 = 8 bytes.
            adaptation_field_len = 8;
        }

        // Calculate if we need stuffing
        size_t data_to_write = remaining_size + (first_packet ? pes_header_len : 0);
        
        if (data_to_write < (available_payload_space - adaptation_field_len)) {
            // Last packet, need stuffing
            size_t space_for_data = available_payload_space - adaptation_field_len;
             // We need to extend adaptation field to consume space
            size_t extra_stuffing = space_for_data - data_to_write;
            adaptation_field_len += extra_stuffing;
            
            // Limit adaptation field length (max 183)
            // But we can handle it usually.
        }
        
        // AFC bits
        bool has_adaptation = (adaptation_field_len > 0);
        bool has_payload = (data_to_write > 0); // Always true unless empty stream?
        
        uint8_t afc = 0;
        if (has_adaptation) afc |= 0x02;
        if (has_payload) afc |= 0x01;
        *p++ = (afc << 4) | (continuity_counter_video_ & 0x0F);
        continuity_counter_video_++;
        
        if (has_adaptation) {
            *p++ = adaptation_field_len - 1; // Length excluding length byte
            if (adaptation_field_len > 1) {
                uint8_t flags = 0;
                if (has_pcr) flags |= 0x10; // PCR flag
                if (first_packet && keyframe) flags |= 0x40; // Random Access Indicator
                *p++ = flags;
                
                if (has_pcr) {
                    // Write PCR (same as PTS logic essentially, but 6 bytes)
                    // base(33) | res(6) | ext(9)
                    // Use PTS as base, ext=0
                    uint64_t pcr_base = pts_90khz; 
                    // 33 bits:
                    *p++ = (pcr_base >> 25) & 0xFF;
                    *p++ = (pcr_base >> 17) & 0xFF;
                    *p++ = (pcr_base >> 9) & 0xFF;
                    *p++ = (pcr_base >> 1) & 0xFF;
                    *p++ = ((pcr_base << 7) & 0x80) | 0x7E | 0x00; // Base low + res(6) + ext high
                    *p++ = 0x00; // ext low
                    
                    // Stuffing bytes
                    for (size_t i = 0; i < adaptation_field_len - 8; i++) {
                        *p++ = 0xFF;
                    }
                } else {
                    // Just stuffing
                     for (size_t i = 0; i < adaptation_field_len - 2; i++) {
                        *p++ = 0xFF;
                    }
                }
            }
        }
        
        // Write Payload
        // pes header first?
        if (first_packet) {
             memcpy(p, pes_header, pes_header_len);
             p += pes_header_len;
        }
        
        size_t current_space = &packet[TS_PACKET_SIZE] - p;
        size_t chunk = (remaining_size < current_space) ? remaining_size : current_space;
        
        if (chunk > 0) {
            memcpy(p, current_payload, chunk);
            current_payload += chunk;
            remaining_size -= chunk;
        }
        
        bufferPacket(packet);
        first_packet = false;
    }
}
