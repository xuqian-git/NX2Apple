#pragma once
#include <stdint.h>
// Audio callbacks never wait, allocate, or send network packets.
void nx_stream_audio(const void *source, const int16_t *pcm, unsigned frames, unsigned channels);
int nx_stream_enabled(void);
int nx_stream_black(void);
void nx_stream_stop(void);
#ifdef __OBJC__
#import <Metal/Metal.h>
void nx_stream_capture(id<MTLTexture> source, id<MTLCommandBuffer> command, const int crop[4]);
#endif
