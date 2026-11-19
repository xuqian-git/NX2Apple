# NXST v1 LAN prototype

IPv4 UDP media destination port 48000. Discovery uses UDP 48001 and Bonjour service `_nxstream._udp`. A request is exactly the eight ASCII bytes `NXDISC01`. The reply is a UTF-8 JSON object (at most 4096 bytes) containing `protocol: "NXDISC01"`, stable receiver `id`, display `name`, physical window `width` and `height`, `mediaPort: 48000`, and an ordered `modes` array such as `["2560x1600", "1920x1200"]`. Modes are hardware H.264 decoder-supported sizes at 60 fps, native display aspect first, followed by smaller sizes. The sender validates dimensions and attempts hardware encoding in that order. Dimensions must be even, at least 320x240, at most 4096 on either axis and at most 4096x2160 pixels.

No user pairing token is required. The v1 header's compatibility field is fixed to 1 by the apps. Legacy explicit `DM_STREAM_TOKEN` remains available to the sender's diagnostic harness only. This is not authentication or encryption. A trusted LAN is required. The sender connects its UDP socket to the selected receiver, so feedback is accepted only from that endpoint. Discovery does not defeat hotspot client isolation.

All header integers use network byte order. Media payloads contain Annex B H.264 access units (video) or signed little-endian interleaved PCM16 (audio). Maximum datagram length is 1200 bytes: 48 bytes of header and up to 1152 bytes of payload.

| Offset | Bytes | Meaning |
|---|---|---|
| 0 | 4 | ASCII NXST |
| 4 | 1 | Version 1 |
| 5 | 1 | Type: 1 video, 2 audio |
| 6 | 2 | Flags: bit 0 keyframe |
| 8 | 4 | Sequence, separately incremented for video and audio |
| 12 | 8 | Sender uptime timestamp, microseconds |
| 20 | 2 | Video width |
| 22 | 2 | Video height |
| 24 | 2 | Audio channels, zero for video |
| 26 | 2 | Reserved zero |
| 28 | 4 | Audio sample rate 48000 |
| 32 | 2 | Fragment index, starting at zero |
| 34 | 2 | Fragment count |
| 36 | 4 | Total media frame bytes, at most 2 MiB |
| 40 | 8 | Compatibility field (1) |

Every nonfinal fragment has 1152 bytes. A keyframe includes SPS and PPS. Frame metadata must agree between fragments. The receiver bounds in-progress frames to 8 and expires incomplete frames after 60 ms. Duplicates and malformed datagrams do not add data. The compressed video queue holds at most 3 access units; overflow or a sequence gap requires an IDR before resuming. PCM blocks have a bounded queue, with an additional playback-head check to discard excess latency. There is no WAN congestion controller or packet-level retransmission.

Feedback is 16 bytes: ASCII NXKF, big-endian uint32 version 1, big-endian uint64 token. It requests a keyframe and is rate-limited by the receiver to once per 250 ms. After two seconds without video, the receiver releases its peer address and sequence state, allowing another game framework to reconnect with the same compatibility field. Sequence wrap is not supported in a continuous session (at 60 fps this takes over two years).

PCM channel order: FL, FR, FC, LFE, BL, BR, SL, SR, TFL, TFR, TBL, TBR (take the first 2, 6, 8 or 12 positions). The current game integration captures one final output and usually supplies 2 or 6 channels. The Android sink only attempts advertised direct multichannel support and otherwise downmixes. Twelve channels do not signify Atmos. Source timestamps are carried for diagnostics and future synchronization, but the first prototype plays promptly rather than implementing clock synchronization or adaptive audio resampling; long-session A/V drift remains a device-validation item.

SDR color metadata uses BT.709 primaries and matrix with an sRGB transfer function. The GPU samples the non-sRGB view of the display-encoded source and renders to a BGRA pixel-buffer texture without a second gamma encoding, matching the engine scan-out; VideoToolbox performs hardware H.264 encoding. Encoded size is independent of the game's internal resolution.
