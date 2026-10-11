#ifndef VIDEO_CONFIG_H
#define VIDEO_CONFIG_H

#ifdef __cplusplus
extern "C" {
#endif

typedef enum {
    ASPECT_RATIO_AUTO = 0, /* Fill screen (e.g. 19.5:9, 20:9) */
    ASPECT_RATIO_16_9 = 1, /* Standard 16:9 widescreen */
    ASPECT_RATIO_4_3  = 2  /* Classic 4:3 pillarbox */
} AspectRatioMode;

typedef enum {
    RENDER_SCALE_NATIVE = 0, /* Full device display resolution */
    RENDER_SCALE_720P   = 1, /* 720p internal FBO */
    RENDER_SCALE_480P   = 2  /* 480p retro Saturn FBO */
} RenderScaleMode;

typedef struct {
    AspectRatioMode aspectRatio;
    RenderScaleMode renderScale;
    int hudSafeZone43;       /* 1 = keep HUD in centered 4:3 safe zone, 0 = anchor to screen edges */
    float fovMultiplier;     /* 1.0f default (range 0.8f - 1.5f) */
} VideoConfig;

void VideoConfig_Init(const char *basePath);
void VideoConfig_Load(void);
void VideoConfig_Save(void);
const VideoConfig *VideoConfig_Get(void);
void VideoConfig_Set(const VideoConfig *config);

/* Helper getters */
AspectRatioMode VideoConfig_GetAspectRatio(void);
RenderScaleMode VideoConfig_GetRenderScale(void);
int VideoConfig_GetHudSafeZone(void);
float VideoConfig_GetFovMultiplier(void);

/* Computes target aspect ratio (width / height) given full drawable dimensions */
float VideoConfig_GetTargetAspect(int fullW, int fullH);

#ifdef __cplusplus
}
#endif

#endif /* VIDEO_CONFIG_H */
