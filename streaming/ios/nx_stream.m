#import "nx_stream.h"
#import <VideoToolbox/VideoToolbox.h>
#import <CoreVideo/CoreVideo.h>
#import <QuartzCore/QuartzCore.h>
#import <os/lock.h>
#include <stdatomic.h>
#include <arpa/inet.h>
#include <sys/socket.h>
#include <fcntl.h>
#include <unistd.h>
#include <pthread.h>
#include <errno.h>

// A bounded, LAN-only prototype. The compatibility field is not authentication.
static int sockfd=-1, width=1920, height=1080;
static uint64_t session_token;
static _Atomic int running, busy, failed, force_key=1;
static uint32_t video_seq, audio_seq;
// Diagnostics reported to the receiver in NXPO replies. Cumulative counters wrap; encode_max resets per reply.
static _Atomic uint32_t stat_send_errors, stat_captured, stat_skipped, stat_encode_max, stat_errno, stat_packets;
static int attempted;
static VTCompressionSessionRef encoder;
static CVPixelBufferPoolRef pool;
static CVMetalTextureCacheRef cache;
static id<MTLRenderPipelineState> pipeline;
static dispatch_queue_t video_queue;
static pthread_t audio_thread;
static os_unfair_lock audio_lock=OS_UNFAIR_LOCK_INIT;
#define AUDIO_SLOTS 24
#define AUDIO_FRAMES 240
struct audio_slot { uint64_t pts; unsigned frames, channels; int16_t pcm[AUDIO_FRAMES*12]; };
static struct audio_slot audio_ring[AUDIO_SLOTS];
static unsigned audio_read, audio_write;
static const void *audio_source;
static uint64_t audio_source_at;
static void be16(uint8_t *p,uint16_t v){p[0]=v>>8;p[1]=v;}
static void be32(uint8_t *p,uint32_t v){be16(p,v>>16);be16(p+2,v);}
static void be64(uint8_t *p,uint64_t v){be32(p,v>>32);be32(p+4,v);}
static uint64_t uptime_us(void){return clock_gettime_nsec_np(CLOCK_UPTIME_RAW)/1000;}
int nx_stream_enabled(void){const char *s=getenv("DM_STREAM_HOST");return s&&*s&&!atomic_load(&failed);}
int nx_stream_black(void){const char *s=getenv("DM_STREAM_BLACK");return nx_stream_enabled()&&(!s||atoi(s)!=0);}
static void send_frame(const uint8_t *data,size_t len,int type,int key,uint64_t pts,unsigned channels){
    if(!atomic_load(&running)||!len)return;
    if(len>2*1024*1024){if(type==1)atomic_store(&force_key,1);return;}
    uint32_t seq=type==1?++video_seq:++audio_seq;
    size_t count=(len+1151)/1152;
    for(size_t i=0;i<count;i++){
        uint8_t packet[1200]={0}; size_t n=MIN((size_t)1152,len-i*1152);
        memcpy(packet,"NXST",4);packet[4]=1;packet[5]=type;be16(packet+6,key);be32(packet+8,seq);be64(packet+12,pts);
        be16(packet+20,width);be16(packet+22,height);be16(packet+24,channels);be32(packet+28,48000);
        be16(packet+32,i);be16(packet+34,count);be32(packet+36,(uint32_t)len);be64(packet+40,session_token);
        memcpy(packet+48,data+i*1152,n);
        if(send(sockfd,packet,n+48,0)!=(ssize_t)(n+48)){atomic_store(&stat_errno,errno);atomic_fetch_add(&stat_send_errors,1);if(type==1)atomic_store(&force_key,1);break;}
        atomic_fetch_add(&stat_packets,1);
        // Bound instantaneous bursts without adding a frame queue.
        if(type==1 && i%16==15)usleep(100);
    }
}
static void *audio_main(void *arg){
    (void)arg;
    while(atomic_load(&running)){
        struct audio_slot a; int have=0;
        os_unfair_lock_lock(&audio_lock);
        if(audio_read!=audio_write){a=audio_ring[audio_read%AUDIO_SLOTS];audio_read++;have=1;}
        os_unfair_lock_unlock(&audio_lock);
        if(have){if((int64_t)(uptime_us()-a.pts)<60000)send_frame((uint8_t *)a.pcm,a.frames*a.channels*2,2,0,a.pts,a.channels);}
        else usleep(1000);
        uint8_t p[64],expected[8];ssize_t n=recv(sockfd,p,sizeof p,0);be64(expected,session_token);
        if(n==16&&!memcmp(p,"NXKF\0\0\0\1",8)&&!memcmp(expected,p+8,8))atomic_store(&force_key,1);
        // Clock probe: echo the receiver time with this clock (the video timestamp clock) and sender counters.
        if(n==24&&!memcmp(p,"NXPG\0\0\0\1",8)&&!memcmp(expected,p+8,8)){
            uint8_t r[56];memcpy(r,"NXPO\0\0\0\1",8);memcpy(r+8,expected,8);memcpy(r+16,p+16,8);be64(r+24,uptime_us());
            be32(r+32,atomic_load(&stat_send_errors));be32(r+36,atomic_load(&stat_captured));be32(r+40,atomic_load(&stat_skipped));
            be32(r+44,atomic_exchange(&stat_encode_max,0));be32(r+48,atomic_load(&stat_errno));be32(r+52,atomic_load(&stat_packets));
            send(sockfd,r,sizeof r,0);
        }
    }
    return NULL;
}
void nx_stream_audio(const void *source,const int16_t *pcm,unsigned frames,unsigned channels){
    if(!atomic_load(&running)||!(channels==2||channels==6||channels==8||channels==12))return;
    if(!os_unfair_lock_trylock(&audio_lock))return;
    uint64_t now=uptime_us();
    // Select one final output, not interleaved unrelated audio devices. A new output can take over after silence.
    if(audio_source!=source && audio_source && now-audio_source_at<500000){os_unfair_lock_unlock(&audio_lock);return;}
    audio_source=source;audio_source_at=now;
    for(unsigned offset=0;offset<frames;offset+=AUDIO_FRAMES){
        if(audio_write-audio_read>=AUDIO_SLOTS)break;
        struct audio_slot *a=&audio_ring[audio_write%AUDIO_SLOTS];
        a->frames=MIN(AUDIO_FRAMES,frames-offset);a->channels=channels;a->pts=now+offset*1000000ull/48000;
        memcpy(a->pcm,pcm+offset*channels,a->frames*channels*2);audio_write++;
    }
    os_unfair_lock_unlock(&audio_lock);
}
static void encoded(void *ctx,void *ref,OSStatus status,VTEncodeInfoFlags flags,CMSampleBufferRef sample){
    (void)ctx;(void)ref;(void)flags;
    if(status||!sample){atomic_store(&busy,0);atomic_store(&force_key,1);return;}
    uint64_t pts=(uint64_t)(CMTimeGetSeconds(CMSampleBufferGetPresentationTimeStamp(sample))*1000000),now=uptime_us();
    uint32_t took=now>pts?(uint32_t)MIN(now-pts,(uint64_t)UINT32_MAX):0,seen=atomic_load(&stat_encode_max);
    while(took>seen&&!atomic_compare_exchange_weak(&stat_encode_max,&seen,took)){}
    CFRetain(sample);
    dispatch_async(video_queue,^{
        @autoreleasepool {
            CFArrayRef attachments=CMSampleBufferGetSampleAttachmentsArray(sample,false);
            BOOL key=!attachments||!CFDictionaryContainsKey(CFArrayGetValueAtIndex(attachments,0),kCMSampleAttachmentKey_NotSync);
            NSMutableData *out=[NSMutableData data];const uint8_t start[]={0,0,0,1};
            CMFormatDescriptionRef format=CMSampleBufferGetFormatDescription(sample);int nalSize=4;
            const uint8_t *ps;size_t size,count;
            if(CMVideoFormatDescriptionGetH264ParameterSetAtIndex(format,0,&ps,&size,&count,&nalSize)==noErr && key){
                for(size_t i=0;i<count;i++)if(CMVideoFormatDescriptionGetH264ParameterSetAtIndex(format,i,&ps,&size,NULL,NULL)==noErr){[out appendBytes:start length:4];[out appendBytes:ps length:size];}
            }
            CMBlockBufferRef block=CMSampleBufferGetDataBuffer(sample);size_t length=CMBlockBufferGetDataLength(block);
            NSMutableData *avcc=[NSMutableData dataWithLength:length];
            if(nalSize==4 && CMBlockBufferCopyDataBytes(block,0,length,avcc.mutableBytes)==noErr){
                const uint8_t *p=avcc.bytes;size_t offset=0;
                while(offset+4<=length){uint32_t n;memcpy(&n,p+offset,4);n=ntohl(n);offset+=4;if(n>length-offset){[out setLength:0];break;}[out appendBytes:start length:4];[out appendBytes:p+offset length:n];offset+=n;}
                if(offset==length&&out.length)send_frame(out.bytes,out.length,1,key,(uint64_t)(CMTimeGetSeconds(CMSampleBufferGetPresentationTimeStamp(sample))*1000000),0);
                else atomic_store(&force_key,1);
            }
            CFRelease(sample);atomic_store(&busy,0);
        }
    });
}
static int initialize(id<MTLDevice> device){
    const char *host=getenv("DM_STREAM_HOST"),*token=getenv("DM_STREAM_TOKEN"),*res=getenv("DM_STREAM_SIZE");
    if(!host)return 0;
    session_token=1; // Fixed compatibility field. Pairing never requires user input.
    if(token && strlen(token)==16 && strspn(token,"0123456789abcdefABCDEF")==16)session_token=strtoull(token,NULL,16);

    struct sockaddr_in address={.sin_family=AF_INET,.sin_port=htons(48000)};
    if(inet_pton(AF_INET,host,&address.sin_addr)!=1)return 0;
    sockfd=socket(AF_INET,SOCK_DGRAM,0);if(sockfd<0)return 0;
    int size=1024*1024;setsockopt(sockfd,SOL_SOCKET,SO_SNDBUF,&size,sizeof size);fcntl(sockfd,F_SETFL,O_NONBLOCK);
    if(connect(sockfd,(struct sockaddr *)&address,sizeof address)){close(sockfd);sockfd=-1;return 0;}
    NSDictionary *spec=@{(__bridge NSString *)kVTVideoEncoderSpecification_RequireHardwareAcceleratedVideoEncoder:@YES,(__bridge NSString *)kVTVideoEncoderSpecification_EnableLowLatencyRateControl:@YES};
    const char *advertised=getenv("DM_STREAM_MODES");
    NSString *choices=(res && strcmp(res,"auto")) ? @(res) : (advertised ? @(advertised) : @"1920x1080,1280x720");
    BOOL configured=NO;
    for(NSString *choice in [choices componentsSeparatedByString:@","]){
        int w=0,h=0;char extra;
        if(sscanf(choice.UTF8String,"%dx%d%c",&w,&h,&extra)!=2 || w<320 || h<240 || w>4096 || h>4096 || (w&1) || (h&1) || (int64_t)w*h>4096LL*2160)continue;
        width=w;height=h;
        OSStatus st=VTCompressionSessionCreate(NULL,width,height,kCMVideoCodecType_H264,(__bridge CFDictionaryRef)spec,NULL,NULL,encoded,NULL,&encoder);
        if(st)continue;
        int bitrate=MAX(4000000,MIN(60000000,(int)((int64_t)width*height*9)));
        NSDictionary *props=@{(__bridge NSString *)kVTCompressionPropertyKey_RealTime:@YES,(__bridge NSString *)kVTCompressionPropertyKey_AllowFrameReordering:@NO,(__bridge NSString *)kVTCompressionPropertyKey_ExpectedFrameRate:@60,(__bridge NSString *)kVTCompressionPropertyKey_MaxKeyFrameInterval:@60,(__bridge NSString *)kVTCompressionPropertyKey_AverageBitRate:@(bitrate),(__bridge NSString *)kVTCompressionPropertyKey_ProfileLevel:(__bridge NSString *)kVTProfileLevel_H264_High_AutoLevel};
        if(VTSessionSetProperties(encoder,(__bridge CFDictionaryRef)props)==noErr && VTCompressionSessionPrepareToEncodeFrames(encoder)==noErr){configured=YES;break;}
        VTCompressionSessionInvalidate(encoder);CFRelease(encoder);encoder=NULL;
    }
    if(!configured){fprintf(stderr,"[NXStream] no supported hardware encoding size\n");return 0;}
    NSDictionary *color=@{(__bridge NSString *)kVTCompressionPropertyKey_ColorPrimaries:(__bridge NSString *)kCVImageBufferColorPrimaries_ITU_R_709_2,(__bridge NSString *)kVTCompressionPropertyKey_TransferFunction:(__bridge NSString *)kCVImageBufferTransferFunction_sRGB,(__bridge NSString *)kVTCompressionPropertyKey_YCbCrMatrix:(__bridge NSString *)kCVImageBufferYCbCrMatrix_ITU_R_709_2};
    if(VTSessionSetProperties(encoder,(__bridge CFDictionaryRef)color)!=noErr)return 0;
    NSDictionary *attrs=@{(__bridge NSString *)kCVPixelBufferWidthKey:@(width),(__bridge NSString *)kCVPixelBufferHeightKey:@(height),(__bridge NSString *)kCVPixelBufferPixelFormatTypeKey:@(kCVPixelFormatType_32BGRA),(__bridge NSString *)kCVPixelBufferMetalCompatibilityKey:@YES,(__bridge NSString *)kCVPixelBufferIOSurfacePropertiesKey:@{}};
    if(CVPixelBufferPoolCreate(NULL,NULL,(__bridge CFDictionaryRef)attrs,&pool)!=kCVReturnSuccess)return 0;
    if(CVMetalTextureCacheCreate(NULL,NULL,device,NULL,&cache)!=kCVReturnSuccess)return 0;
    NSString *code=@"#include <metal_stdlib>\nusing namespace metal; struct V {float4 p [[position]];float2 uv;}; struct Crop {float4 rect;float4 edge;}; vertex V vs(uint i [[vertex_id]]){float2 p=float2((i<<1)&2,i&2);return {float4(p*2-1,0,1),float2(p.x,1-p.y)};} fragment float4 fs(V v [[stage_in]],texture2d<float> t [[texture(0)]],constant Crop &crop [[buffer(0)]]){constexpr sampler s(coord::normalized,filter::linear,address::clamp_to_edge);return t.sample(s,clamp(crop.rect.xy+v.uv*crop.rect.zw,crop.edge.xy,crop.edge.zw));}";
    NSError *error=nil;id<MTLLibrary> lib=[device newLibraryWithSource:code options:nil error:&error];
    MTLRenderPipelineDescriptor *d=[MTLRenderPipelineDescriptor new];d.vertexFunction=[lib newFunctionWithName:@"vs"];d.fragmentFunction=[lib newFunctionWithName:@"fs"];d.colorAttachments[0].pixelFormat=MTLPixelFormatBGRA8Unorm;
    pipeline=[device newRenderPipelineStateWithDescriptor:d error:&error];if(!pipeline){fprintf(stderr,"[NXStream] pipeline error: %s\n",error.description.UTF8String);return 0;}
    video_queue=dispatch_queue_create("nxstream.video",DISPATCH_QUEUE_SERIAL);
    atomic_store(&running,1);if(pthread_create(&audio_thread,NULL,audio_main,NULL)){atomic_store(&running,0);return 0;}
    fprintf(stderr,"[NXStream] %dx%d H.264 to %s:48000 (LAN plaintext prototype)\n",width,height,host);
    return 1;
}
void nx_stream_capture(id<MTLTexture> source,id<MTLCommandBuffer> cb,const int crop[4],int flip,double aspect){
    if(!nx_stream_enabled())return;
    if(!atomic_load(&running)){if(attempted)return;attempted=1;if(!initialize(source.device)){atomic_store(&failed,1);fprintf(stderr,"[NXStream] initialization failed; verify host/token/size and hardware encoding\n");return;}}
    if(atomic_exchange(&busy,1)){atomic_fetch_add(&stat_skipped,1);return;}
    CVPixelBufferRef pixel=NULL;CVMetalTextureRef texture=NULL;
    if(CVPixelBufferPoolCreatePixelBuffer(NULL,pool,&pixel)!=kCVReturnSuccess){atomic_store(&busy,0);return;}
    CVBufferSetAttachment(pixel,kCVImageBufferColorPrimariesKey,kCVImageBufferColorPrimaries_ITU_R_709_2,kCVAttachmentMode_ShouldPropagate);
    CVBufferSetAttachment(pixel,kCVImageBufferTransferFunctionKey,kCVImageBufferTransferFunction_sRGB,kCVAttachmentMode_ShouldPropagate);
    CVBufferSetAttachment(pixel,kCVImageBufferYCbCrMatrixKey,kCVImageBufferYCbCrMatrix_ITU_R_709_2,kCVAttachmentMode_ShouldPropagate);
    CVReturn result=CVMetalTextureCacheCreateTextureFromImage(NULL,cache,pixel,NULL,MTLPixelFormatBGRA8Unorm,width,height,0,&texture);
    if(result||!texture){CFRelease(pixel);atomic_store(&busy,0);return;}
    MTLRenderPassDescriptor *pass=[MTLRenderPassDescriptor renderPassDescriptor];pass.colorAttachments[0].texture=CVMetalTextureGetTexture(texture);pass.colorAttachments[0].loadAction=MTLLoadActionClear;pass.colorAttachments[0].clearColor=MTLClearColorMake(0,0,0,1);pass.colorAttachments[0].storeAction=MTLStoreActionStore;
    id<MTLRenderCommandEncoder> r=[cb renderCommandEncoderWithDescriptor:pass];
    if(!r){CFRelease(texture);CFRelease(pixel);atomic_store(&busy,0);return;}
    float uv[8]={0,0,1,1,0.5f/source.width,0.5f/source.height,1-0.5f/source.width,1-0.5f/source.height};
    if(crop&&crop[2]>0&&crop[3]>0){uv[0]=(float)crop[0]/source.width;uv[1]=(float)crop[1]/source.height;uv[2]=(float)crop[2]/source.width;uv[3]=(float)crop[3]/source.height;}
    uv[4]=uv[0]+0.5f/source.width;uv[5]=uv[1]+0.5f/source.height;uv[6]=uv[0]+uv[2]-0.5f/source.width;uv[7]=uv[1]+uv[3]-0.5f/source.height;
    if(flip){uv[1]+=uv[3];uv[3]=-uv[3];}
    double vw=width,vh=height;
    if(aspect>0){if(vw/vh>aspect)vw=vh*aspect;else vh=vw/aspect;}
    [r setViewport:(MTLViewport){(width-vw)/2,(height-vh)/2,vw,vh,0,1}];
    [r setRenderPipelineState:pipeline];[r setFragmentTexture:source atIndex:0];[r setFragmentBytes:uv length:sizeof uv atIndex:0];[r drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];[r endEncoding];
    uint64_t pts=uptime_us();
    [cb addCompletedHandler:^(id<MTLCommandBuffer> done){
        if(done.status==MTLCommandBufferStatusCompleted && atomic_load(&running)){
            NSDictionary *options=atomic_exchange(&force_key,0)?@{(__bridge NSString *)kVTEncodeFrameOptionKey_ForceKeyFrame:@YES}:nil;
            OSStatus st=VTCompressionSessionEncodeFrame(encoder,pixel,CMTimeMake(pts,1000000),CMTimeMake(1,60),(__bridge CFDictionaryRef)options,NULL,NULL);
            if(st){atomic_store(&busy,0);atomic_store(&force_key,1);}else atomic_fetch_add(&stat_captured,1);
        }else atomic_store(&busy,0);
        CFRelease(texture);CFRelease(pixel);
    }];
}
// Called after the engine has drained GPU work, before its framework is unloaded.
void nx_stream_stop(void){
    int was=atomic_exchange(&running,0);if(was)pthread_join(audio_thread,NULL);
    if(encoder){VTCompressionSessionCompleteFrames(encoder,kCMTimeInvalid);VTCompressionSessionInvalidate(encoder);CFRelease(encoder);encoder=NULL;}
    if(video_queue)dispatch_sync(video_queue,^{});
    if(sockfd>=0){close(sockfd);sockfd=-1;}
    if(pool){CFRelease(pool);pool=NULL;}if(cache){CFRelease(cache);cache=NULL;}
    attempted=0;atomic_store(&failed,0);pipeline=nil;video_queue=nil;atomic_store(&busy,0);audio_source=NULL;audio_read=audio_write=0;
}
