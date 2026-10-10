#ifndef SONICR_MOD_VFS_H
#define SONICR_MOD_VFS_H

#include <stddef.h>
#include <stdio.h>

#define MAX_MODS 64
#define MAX_MOD_PATH 1024
#define MAX_MOD_NAME 128
#define MAX_MOD_SUBDIR 128

typedef struct {
    char id[MAX_MOD_NAME];            /* Directory name, e.g. "TestSoapSonic" */
    char name[MAX_MOD_NAME];          /* Name from mod.ini, e.g. "Soap Shoes Sonic" */
    char author[MAX_MOD_NAME];        /* Author from mod.ini */
    char version[32];                 /* Version from mod.ini */
    char description[256];            /* Description from mod.ini */
    char full_path[MAX_MOD_PATH];     /* Absolute directory path to mod folder */
    char include_dir[MAX_MOD_SUBDIR]; /* IncludeDir0, e.g. "files" */
    int disable_blink;                /* DisableBlink (1 = true) */
    int enabled;                      /* 1 = active / enabled in mods_config.json */
} ModEntry;

extern int g_modsEnabled;
extern int g_modDisableBlink;

/* Initialize VFS mod subsystem and scan mods directory */
void VFS_Init(const char *base_path);

/* Rescan mods directory and reload mods_config.json */
void VFS_ScanMods(const char *base_path);

/* Resolve a game asset path through active mods.
 * Returns 1 and fills out_path if an active mod overrides the file;
 * returns 0 otherwise. */
int VFS_ResolvePath(const char *rel_path, char *out_path, size_t out_len);

/* Check if blinking should be disabled by any active mod */
int VFS_GetDisableBlink(void);

/* Get number of loaded mods */
int VFS_GetModCount(void);

/* Get mod entry by index */
const ModEntry *VFS_GetModEntry(int index);

#endif /* SONICR_MOD_VFS_H */
