#!/usr/bin/env python3
"""Apply the fork's isolated streaming integration to an exported engine tree.
Exact anchors fail closed when upstream changes. Never edits the upstream checkout.
"""
import argparse, hashlib, shutil
from pathlib import Path
ROOT=Path(__file__).resolve().parent

def apply(tree):
    tree=Path(tree)
    if (tree/'.git').exists(): raise ValueError('Use an exported engine tree, not a Git checkout')
    if (tree/'.nxstream-overlay').exists(): raise ValueError('Overlay already applied; export a fresh tree')
    changes={}
    def replace(path,old,new):
        text=changes.get(path,(tree/path).read_text())
        if text.count(old)!=1: raise ValueError(f'Upstream integration anchor changed: {path}: {old[:90]}')
        changes[path]=text.replace(old,new)
    gfx='dm/gfx/nvn/nvn_mtl.m'
    replace(gfx,'#import <Metal/Metal.h>','#import <Metal/Metal.h>\n#import "nx_stream.h"')
    replace(gfx,'    const char *latency = getenv("DM_PRESENT_LATENCY");','    if (nx_stream_black()) g_dm_present_link = 0;\n    const char *latency = getenv("DM_PRESENT_LATENCY");')
    replace(gfx,'    int want = g_dm_present_link;','    int want = nx_stream_black() ? 0 : g_dm_present_link;')
    anchor='    // DM_NVN_REGLUM=x0,y0,x1,y1:file (normalized coordinates):'
    replace(gfx,anchor,'''    // NXStream: sample on the existing Metal queue after its producer's wait.
    // Completion owns the frame until the copy finishes, exactly as a local present does.
    if (nx_stream_enabled()) {
        if (nx_stream_black()) {
            static uint64_t next;
            uint64_t now = clock_gettime_nsec_np(CLOCK_UPTIME_RAW);
            uint64_t interval = (uint64_t)(MAX(period, 1.0 / 60.0) * 1e9);
            if (next > now && next - now < 100000000) { struct timespec ts = {0, (long)(next - now)}; nanosleep(&ts, NULL); }
            next = clock_gettime_nsec_np(CLOCK_UPTIME_RAW) + interval;
        }
        // Metal 4 game work and the encoder's Metal command buffer use different queues.
        // Bridge both directions so neither sampling nor the next reuse can race the other.
        static id<MTLSharedEvent> stream_event;
        static uint64_t stream_fence;
        if (!stream_event) stream_event = [g_dev newSharedEvent];
        if (!stream_event) return 0;
        uint64_t ready = ++stream_fence, copied = ++stream_fence;
        pthread_mutex_lock(&g_res_commit_mu);
        [g_q4 signalEvent:stream_event value:ready];
        id<MTLCommandBuffer> stream = q_cb();
        [stream encodeWaitForEvent:stream_event value:ready];
        double picture_width, picture_height; present_shape(src, &picture_width, &picture_height);
        nx_stream_capture(present_raw_src(src), stream, crop, atomic_load(&g_origin_ll), picture_width / picture_height);
        [stream encodeSignalEvent:stream_event value:copied];
        if (nx_stream_black()) {
            pthread_mutex_lock(&g_pq_mu); g_pq_inflight++; pthread_mutex_unlock(&g_pq_mu);
            [stream addCompletedHandler:^(id<MTLCommandBuffer> done) {
                if (done.error) gpu_lost_check(done.error, "stream copy");
                pthread_mutex_lock(&g_pq_mu);
                if (ticket > g_pq_done) g_pq_done = ticket;
                g_pq_inflight--; pthread_cond_broadcast(&g_pq_cv);
                NSArray *retire = nil;
                if (!g_pq_inflight && g_ext_retire.count) { retire = g_ext_retire; g_ext_retire = nil; }
                pthread_mutex_unlock(&g_pq_mu);
                pres_seen(ticket, CACurrentMediaTime());
                if (retire) ext_retire(retire);
            }];
            [stream commit];
            [g_q4 waitForEvent:stream_event value:copied];
            pthread_mutex_unlock(&g_res_commit_mu);
            return 1;
        }
        [stream commit];
        [g_q4 waitForEvent:stream_event value:copied];
        pthread_mutex_unlock(&g_res_commit_mu);
    }
'''+anchor)
    replace(gfx,'    atomic_store(&g_rec_frame, 0);','    nx_stream_stop();\n    atomic_store(&g_rec_frame, 0);')
    audio='dm/host/hle_audio.c'
    replace(audio,'static void pull(aout *a, uint8_t *dst, uint64_t n) {','extern void nx_stream_audio(const void *, const int16_t *, unsigned, unsigned) __attribute__((weak_import));\nextern int nx_stream_black(void) __attribute__((weak_import));\nstatic void pull(aout *a, uint8_t *dst, uint64_t n) {')
    replace(audio,'    if (released) {\n        if (a->event)', '    if (nx_stream_audio) nx_stream_audio(a, s16, (unsigned)(ns / a->ch), a->ch);\n    if (released) {\n        if (a->event)')
    replace(audio,'        if (a->mute) memset(bl->mBuffers[0].mData, 0, bl->mBuffers[0].mDataByteSize);','        if (a->mute || (nx_stream_black && nx_stream_black())) memset(bl->mBuffers[0].mData, 0, bl->mBuffers[0].mDataByteSize);')
    ios='dm/ios/Sources/front_ios.m'
    replace(ios,'    self.fill = dm_picture_fill(getenv("DM_PICTURE_FIT"));','    if (getenv("DM_STREAM_HOST")) setenv("DM_SCREEN_ASPECT", "0", 1); // Receiver modes are all 16:9\n    self.fill = dm_picture_fill(getenv("DM_PICTURE_FIT"));')
    replace(ios,'    [self.view addSubview:self.game];','    [self.view addSubview:self.game];\n    if (getenv("DM_STREAM_HOST")) self.game.backgroundColor = UIColor.blackColor;')
    # Application privacy strings belong in each app template, including the shared shell.
    for p in (tree/'dm').rglob('*.yml'):
        if 'ios' not in p.parts and 'shell' not in p.parts: continue
        text=p.read_text()
        if 'UIFileSharingEnabled: true' in text and 'NSLocalNetworkUsageDescription' not in text:
            lines=text.splitlines(True); output=[]
            for line in lines:
                output.append(line)
                if 'UIFileSharingEnabled: true' in line:
                    indent=line[:len(line)-len(line.lstrip())]
                    output.append(indent+'NSLocalNetworkUsageDescription: "Stream game video and audio to your receiver on the local network."\n')
            changes[str(p.relative_to(tree))]=''.join(output)
    for path,text in changes.items(): (tree/path).write_text(text)
    for name in ('nx_stream.h','nx_stream.m'): shutil.copy2(ROOT/'ios'/name,tree/'dm/gfx/nvn'/name)
    digest=hashlib.sha256()
    for p in [Path(__file__),ROOT/'ios/nx_stream.h',ROOT/'ios/nx_stream.m']: digest.update(p.read_bytes())
    overlay=digest.hexdigest()
    (tree/'.nxstream-overlay').write_text(overlay+'\n')
    identity=tree/'.engine-id'
    if identity.exists(): identity.write_text(identity.read_text().strip().split('-')[0]+'-'+hashlib.sha256((identity.read_text()+overlay).encode()).hexdigest()[:12]+'\n')
    print(f'NXStream overlay applied: {overlay[:12]}')

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('tree');apply(parser.parse_args().tree)
