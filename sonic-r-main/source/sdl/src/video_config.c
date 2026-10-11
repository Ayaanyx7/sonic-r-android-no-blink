/**
 * video_config.c — Video display & widescreen configuration system
 */

#include "video_config.h"
#include <SDL.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <ctype.h>

static VideoConfig s_config = {
    .aspectRatio   = ASPECT_RATIO_AUTO,
    .renderScale   = RENDER_SCALE_NATIVE,
    .hudSafeZone43 = 1,
    .fovMultiplier = 1.0f
};

static char s_basePath[512] = "";
static int s_initialized = 0;

static void parse_json_string(const char *buf, const char *key, char *out_val, size_t max_len)
{
    char search[64];
    snprintf(search, sizeof(search), "\"%s\"", key);
    const char *p = strstr(buf, search);
    if (!p) return;
    const char *colon = strchr(p, ':');
    if (!colon) return;
    const char *quote1 = strchr(colon, '"');
    if (!quote1) return;
    const char *quote2 = strchr(quote1 + 1, '"');
    if (!quote2) return;
    size_t len = (size_t)(quote2 - (quote1 + 1));
    if (len >= max_len) len = max_len - 1;
    strncpy(out_val, quote1 + 1, len);
    out_val[len] = '\0';
}

static int parse_json_bool(const char *buf, const char *key, int default_val)
{
    char search[64];
    snprintf(search, sizeof(search), "\"%s\"", key);
    const char *p = strstr(buf, search);
    if (!p) return default_val;
    const char *colon = strchr(p, ':');
    if (!colon) return default_val;
    colon++;
    while (*colon && isspace((unsigned char)*colon)) colon++;
    if (strncmp(colon, "true", 4) == 0 || strncmp(colon, "1", 1) == 0) return 1;
    if (strncmp(colon, "false", 5) == 0 || strncmp(colon, "0", 1) == 0) return 0;
    return default_val;
}

static float parse_json_float(const char *buf, const char *key, float default_val)
{
    char search[64];
    snprintf(search, sizeof(search), "\"%s\"", key);
    const char *p = strstr(buf, search);
    if (!p) return default_val;
    const char *colon = strchr(p, ':');
    if (!colon) return default_val;
    colon++;
    while (*colon && isspace((unsigned char)*colon)) colon++;
    float val = (float)atof(colon);
    if (val <= 0.0f) return default_val;
    return val;
}

void VideoConfig_Init(const char *basePath)
{
    if (basePath && *basePath) {
        strncpy(s_basePath, basePath, sizeof(s_basePath) - 1);
        s_basePath[sizeof(s_basePath) - 1] = '\0';
    } else {
        s_basePath[0] = '\0';
    }
    s_initialized = 1;
    VideoConfig_Load();
}

void VideoConfig_Load(void)
{
    char path[1024];
    if (s_basePath[0]) {
        snprintf(path, sizeof(path), "%s/video_config.json", s_basePath);
    } else {
        snprintf(path, sizeof(path), "video_config.json");
    }

    FILE *fp = fopen(path, "r");
    if (!fp) {
        SDL_Log("VideoConfig: '%s' not found, using defaults", path);
        return;
    }

    char buf[4096];
    size_t n = fread(buf, 1, sizeof(buf) - 1, fp);
    fclose(fp);
    buf[n] = '\0';

    char aspectStr[32] = "";
    char scaleStr[32] = "";
    parse_json_string(buf, "aspectRatio", aspectStr, sizeof(aspectStr));
    parse_json_string(buf, "renderScale", scaleStr, sizeof(scaleStr));

    if (strcmp(aspectStr, "16:9") == 0) {
        s_config.aspectRatio = ASPECT_RATIO_16_9;
    } else if (strcmp(aspectStr, "4:3") == 0) {
        s_config.aspectRatio = ASPECT_RATIO_4_3;
    } else {
        s_config.aspectRatio = ASPECT_RATIO_AUTO;
    }

    if (strcmp(scaleStr, "720p") == 0) {
        s_config.renderScale = RENDER_SCALE_720P;
    } else if (strcmp(scaleStr, "480p") == 0) {
        s_config.renderScale = RENDER_SCALE_480P;
    } else {
        s_config.renderScale = RENDER_SCALE_NATIVE;
    }

    s_config.hudSafeZone43 = parse_json_bool(buf, "hudSafeZone43", 1);
    s_config.fovMultiplier = parse_json_float(buf, "fovMultiplier", 1.0f);
    if (s_config.fovMultiplier < 0.7f) s_config.fovMultiplier = 0.7f;
    if (s_config.fovMultiplier > 2.0f) s_config.fovMultiplier = 2.0f;

    SDL_Log("VideoConfig loaded from '%s': aspect=%d, scale=%d, hudSafe=%d, fov=%.2f",
            path, (int)s_config.aspectRatio, (int)s_config.renderScale,
            s_config.hudSafeZone43, s_config.fovMultiplier);
}

void VideoConfig_Save(void)
{
    char path[1024];
    if (s_basePath[0]) {
        snprintf(path, sizeof(path), "%s/video_config.json", s_basePath);
    } else {
        snprintf(path, sizeof(path), "video_config.json");
    }

    FILE *fp = fopen(path, "w");
    if (!fp) {
        SDL_Log("VideoConfig: failed to open '%s' for writing", path);
        return;
    }

    const char *aspectStr = (s_config.aspectRatio == ASPECT_RATIO_16_9) ? "16:9" :
                            (s_config.aspectRatio == ASPECT_RATIO_4_3)  ? "4:3" : "auto";
    const char *scaleStr  = (s_config.renderScale == RENDER_SCALE_720P) ? "720p" :
                            (s_config.renderScale == RENDER_SCALE_480P) ? "480p" : "native";

    fprintf(fp, "{\n"
                "  \"aspectRatio\": \"%s\",\n"
                "  \"renderScale\": \"%s\",\n"
                "  \"hudSafeZone43\": %s,\n"
                "  \"fovMultiplier\": %.2f\n"
                "}\n",
                aspectStr, scaleStr,
                s_config.hudSafeZone43 ? "true" : "false",
                s_config.fovMultiplier);
    fclose(fp);
    SDL_Log("VideoConfig saved to '%s'", path);
}

const VideoConfig *VideoConfig_Get(void)
{
    return &s_config;
}

void VideoConfig_Set(const VideoConfig *config)
{
    if (config) {
        s_config = *config;
        if (s_config.fovMultiplier < 0.7f) s_config.fovMultiplier = 0.7f;
        if (s_config.fovMultiplier > 2.0f) s_config.fovMultiplier = 2.0f;
    }
}

AspectRatioMode VideoConfig_GetAspectRatio(void)
{
    return s_config.aspectRatio;
}

RenderScaleMode VideoConfig_GetRenderScale(void)
{
    return s_config.renderScale;
}

int VideoConfig_GetHudSafeZone(void)
{
    return s_config.hudSafeZone43;
}

float VideoConfig_GetFovMultiplier(void)
{
    return s_config.fovMultiplier;
}

float VideoConfig_GetTargetAspect(int fullW, int fullH)
{
    switch (s_config.aspectRatio) {
        case ASPECT_RATIO_16_9:
            return 16.0f / 9.0f;
        case ASPECT_RATIO_4_3:
            return 4.0f / 3.0f;
        case ASPECT_RATIO_AUTO:
        default:
            if (fullW > 0 && fullH > 0) {
                return (float)fullW / (float)fullH;
            }
            return 16.0f / 9.0f;
    }
}
