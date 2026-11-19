#import "../ios/nx_stream.h"
#import <Foundation/Foundation.h>
#include <unistd.h>
int main(void){@autoreleasepool{
    id<MTLDevice> device=MTLCreateSystemDefaultDevice();if(!device)return 2;
    id<MTLCommandQueue> queue=[device newCommandQueue];
    MTLTextureDescriptor *d=[MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatBGRA8Unorm_sRGB width:640 height:360 mipmapped:NO];d.usage=MTLTextureUsageRenderTarget|MTLTextureUsageShaderRead;
    id<MTLTexture> t=[device newTextureWithDescriptor:d];
    for(int i=0;i<90;i++){@autoreleasepool{
        id<MTLCommandBuffer> cb=[queue commandBuffer];MTLRenderPassDescriptor *p=[MTLRenderPassDescriptor renderPassDescriptor];p.colorAttachments[0].texture=t;p.colorAttachments[0].loadAction=MTLLoadActionClear;p.colorAttachments[0].storeAction=MTLStoreActionStore;p.colorAttachments[0].clearColor=MTLClearColorMake(.5,0,(i%60)/60.0,1);
        id<MTLRenderCommandEncoder> r=[cb renderCommandEncoderWithDescriptor:p];[r endEncoding];
        nx_stream_capture(t,cb,NULL);[cb commit];[cb waitUntilCompleted];
        int16_t audio[240*6]={0};for(int j=0;j<240;j++)audio[j*6+2]=1000;nx_stream_audio((void *)1,audio,240,6);
        usleep(16667);
    }}
    nx_stream_stop();return 0;
}}
