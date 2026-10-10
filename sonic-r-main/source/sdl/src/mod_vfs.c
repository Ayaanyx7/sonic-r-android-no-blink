/**
 * mod_vfs.c — Native Virtual File System (VFS) Mod Loader for Sonic R Android / SDL.
 *
 * Implements:
 * - Scanning and loading mods from <base_path>/mods (or ./mods).
 * - Parser for mod.ini (Name, Author, Version, Description, IncludeDir0, DisableBlink).
 * - Parser for mods_config.json (modsEnabled, activeMods list).
 * - Multi-layout path resolution: IncludeDir0, files/, and mod root.
 * - Robust case-insensitive directory traversal for POSIX/Android file systems.
 * - Dynamic DisableBlink state calculation for character animation hooks.
 */

#include "mod_vfs.h"
#include <SDL.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <ctype.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <errno.h>

#ifdef _WIN32
#include <direct.h>
#include <io.h>
#define sr_strcasecmp _stricmp
#else
#include <dirent.h>
#include <unistd.h>
#include <strings.h>
#define sr_strcasecmp strcasecmp
#endif

int g_modsEnabled = 1;
int g_modDisableBlink = 0;

static ModEntry s_mods[MAX_MODS];
static int s_modCount = 0;
static char s_basePath[MAX_MOD_PATH] = "";
static char s_modsDir[MAX_MOD_PATH] = "";
static int s_initialized = 0;

/* Helper: trim whitespace in-place */
static char *trim_whitespace(char *str)
{
    if (!str) return NULL;
    while (*str && isspace((unsigned char)*str)) {
        str++;
    }
    if (*str == '\0') return str;

    char *end = str + strlen(str) - 1;
    while (end > str && isspace((unsigned char)*end)) {
        *end = '\0';
        end--;
    }
    return str;
}

/* Helper: checks if a directory exists */
static int is_directory(const char *path)
{
    struct stat st;
    if (stat(path, &st) == 0 && S_ISDIR(st.st_mode)) {
        return 1;
    }
    return 0;
}

/* Helper: checks if a regular file exists */
static int is_regular_file(const char *path)
{
    struct stat st;
    if (stat(path, &st) == 0 && !S_ISDIR(st.st_mode)) {
        return 1;
    }
    return 0;
}

/**
 * Searches for a file within a base directory on case-sensitive POSIX filesystems
 * by matching each path segment case-insensitively using opendir/readdir.
 */
static int vfs_find_case_insensitive(const char *dir_root, const char *rel_path,
                                     char *out_path, size_t out_len)
{
    if (!dir_root || !*dir_root || !rel_path || !*rel_path || !out_path || out_len == 0) {
        return 0;
    }

#ifdef _WIN32
    /* Windows NTFS is case-insensitive by default */
    char candidate[MAX_MOD_PATH];
    snprintf(candidate, sizeof(candidate), "%s/%s", dir_root, rel_path);
    for (char *p = candidate; *p; p++) {
        if (*p == '\\') *p = '/';
    }
    if (is_regular_file(candidate)) {
        strncpy(out_path, candidate, out_len - 1);
        out_path[out_len - 1] = '\0';
        return 1;
    }
    return 0;
#else
    /* Normalize rel_path and copy */
    char path_copy[MAX_MOD_PATH];
    size_t pl = strlen(rel_path);
    if (pl >= sizeof(path_copy)) {
        return 0;
    }
    for (size_t i = 0; i <= pl; i++) {
        path_copy[i] = (rel_path[i] == '\\') ? '/' : rel_path[i];
    }

    char current[MAX_MOD_PATH];
    strncpy(current, dir_root, sizeof(current) - 1);
    current[sizeof(current) - 1] = '\0';

    /* Strip trailing slashes from current */
    size_t cl = strlen(current);
    while (cl > 0 && current[cl - 1] == '/') {
        current[--cl] = '\0';
    }

    if (!is_directory(current)) {
        return 0;
    }

    char *saveptr = NULL;
    char *token = strtok_r(path_copy, "/", &saveptr);

    while (token != NULL) {
        if (strcmp(token, ".") == 0) {
            token = strtok_r(NULL, "/", &saveptr);
            continue;
        }
        if (strcmp(token, "..") == 0) {
            /* Prevent directory traversal outside root */
            return 0;
        }

        DIR *d = opendir(current);
        if (!d) {
            return 0;
        }

        struct dirent *entry;
        int found = 0;
        char matched_name[256] = "";

        while ((entry = readdir(d)) != NULL) {
            if (sr_strcasecmp(entry->d_name, token) == 0) {
                strncpy(matched_name, entry->d_name, sizeof(matched_name) - 1);
                matched_name[sizeof(matched_name) - 1] = '\0';
                found = 1;
                break;
            }
        }
        closedir(d);

        if (!found) {
            return 0;
        }

        size_t cur_len = strlen(current);
        int written = snprintf(current + cur_len, sizeof(current) - cur_len, "/%s", matched_name);
        if (written < 0 || (size_t)written >= sizeof(current) - cur_len) {
            return 0;
        }

        token = strtok_r(NULL, "/", &saveptr);
    }

    /* Final match must be a regular file */
    if (is_regular_file(current)) {
        strncpy(out_path, current, out_len - 1);
        out_path[out_len - 1] = '\0';
        return 1;
    }

    return 0;
#endif
}

/**
 * Parses a mod.ini file located in mod_dir.
 */
static int parse_mod_ini(const char *mod_dir, ModEntry *entry)
{
    /* Find mod.ini case-insensitively */
    char ini_path[MAX_MOD_PATH];
    snprintf(ini_path, sizeof(ini_path), "%s/mod.ini", mod_dir);

    FILE *fp = fopen(ini_path, "r");
    if (!fp) {
        snprintf(ini_path, sizeof(ini_path), "%s/MOD.INI", mod_dir);
        fp = fopen(ini_path, "r");
    }
    if (!fp) {
        char matched[MAX_MOD_PATH];
        if (vfs_find_case_insensitive(mod_dir, "mod.ini", matched, sizeof(matched))) {
            fp = fopen(matched, "r");
        }
    }

    if (!fp) {
        return 0;
    }

    char line[512];
    while (fgets(line, sizeof(line), fp)) {
        char *trimmed = trim_whitespace(line);
        if (*trimmed == '\0' || *trimmed == '#' || *trimmed == ';' || *trimmed == '[') {
            continue;
        }

        char *eq = strchr(trimmed, '=');
        if (!eq) {
            continue;
        }

        *eq = '\0';
        char *key = trim_whitespace(trimmed);
        char *val = trim_whitespace(eq + 1);

        if (sr_strcasecmp(key, "Name") == 0) {
            strncpy(entry->name, val, sizeof(entry->name) - 1);
        } else if (sr_strcasecmp(key, "Author") == 0) {
            strncpy(entry->author, val, sizeof(entry->author) - 1);
        } else if (sr_strcasecmp(key, "Version") == 0) {
            strncpy(entry->version, val, sizeof(entry->version) - 1);
        } else if (sr_strcasecmp(key, "Description") == 0) {
            strncpy(entry->description, val, sizeof(entry->description) - 1);
        } else if (sr_strcasecmp(key, "IncludeDir0") == 0) {
            strncpy(entry->include_dir, val, sizeof(entry->include_dir) - 1);
        } else if (sr_strcasecmp(key, "DisableBlink") == 0) {
            if (strcmp(val, "1") == 0 || sr_strcasecmp(val, "true") == 0) {
                entry->disable_blink = 1;
            } else {
                entry->disable_blink = 0;
            }
        }
    }

    fclose(fp);
    return 1;
}

/**
 * Reads mods_config.json from the mods root directory.
 * Populates out_active_ids array and returns number of active mods.
 * Returns -1 if mods_config.json was not found.
 */
static int read_mods_config(const char *mods_dir, int *out_mods_enabled,
                            char active_ids[MAX_MODS][MAX_MOD_NAME], int max_active)
{
    char config_path[MAX_MOD_PATH];
    snprintf(config_path, sizeof(config_path), "%s/mods_config.json", mods_dir);

    FILE *fp = fopen(config_path, "r");
    if (!fp) {
        return -1;
    }

    /* Read up to 32KB of JSON */
    char buf[32768];
    size_t n = fread(buf, 1, sizeof(buf) - 1, fp);
    fclose(fp);
    buf[n] = '\0';

    *out_mods_enabled = 1;
    char *me = strstr(buf, "\"modsEnabled\"");
    if (me) {
        char *colon = strchr(me, ':');
        if (colon) {
            colon++;
            while (*colon && isspace((unsigned char)*colon)) colon++;
            if (strncmp(colon, "false", 5) == 0 || strncmp(colon, "0", 1) == 0) {
                *out_mods_enabled = 0;
            }
        }
    }

    int count = 0;
    char *am = strstr(buf, "\"activeMods\"");
    if (am) {
        char *bracket = strchr(am, '[');
        if (bracket) {
            char *p = bracket + 1;
            while (*p && *p != ']' && count < max_active) {
                if (*p == '"') {
                    p++;
                    char *start = p;
                    while (*p && *p != '"' && *p != ']') p++;
                    if (*p == '"') {
                        size_t len = p - start;
                        if (len > 0 && len < MAX_MOD_NAME) {
                            strncpy(active_ids[count], start, len);
                            active_ids[count][len] = '\0';
                            count++;
                        }
                        p++;
                    }
                } else {
                    p++;
                }
            }
        }
    }

    return count;
}

void VFS_Init(const char *base_path)
{
    if (base_path && *base_path) {
        strncpy(s_basePath, base_path, sizeof(s_basePath) - 1);
        s_basePath[sizeof(s_basePath) - 1] = '\0';
    } else {
        s_basePath[0] = '\0';
    }

    VFS_ScanMods(s_basePath);
    s_initialized = 1;
}

void VFS_ScanMods(const char *base_path)
{
    s_modCount = 0;
    g_modDisableBlink = 0;
    memset(s_mods, 0, sizeof(s_mods));

    /* 1. Determine mods directory */
    if (base_path && *base_path) {
        snprintf(s_modsDir, sizeof(s_modsDir), "%s/mods", base_path);
    } else {
        snprintf(s_modsDir, sizeof(s_modsDir), "mods");
    }

    if (!is_directory(s_modsDir)) {
        /* Fallback: try relative ./mods */
        if (strcmp(s_modsDir, "mods") != 0 && is_directory("mods")) {
            strncpy(s_modsDir, "mods", sizeof(s_modsDir) - 1);
        } else {
            SDL_Log("VFS: Mods directory not found at '%s'", s_modsDir);
            return;
        }
    }

    SDL_Log("VFS: Scanning mods in '%s'...", s_modsDir);

    /* 2. Read mods_config.json if present */
    char active_ids[MAX_MODS][MAX_MOD_NAME];
    int active_count = read_mods_config(s_modsDir, &g_modsEnabled, active_ids, MAX_MODS);
    int has_config = (active_count >= 0);

    /* 3. Scan subdirectories in s_modsDir */
#ifdef _WIN32
    char search_pattern[MAX_MOD_PATH];
    snprintf(search_pattern, sizeof(search_pattern), "%s/*", s_modsDir);
    struct _finddata_t fileinfo;
    intptr_t handle = _findfirst(search_pattern, &fileinfo);
    if (handle != -1) {
        do {
            if (!(fileinfo.attrib & _A_SUBDIR)) continue;
            if (strcmp(fileinfo.name, ".") == 0 || strcmp(fileinfo.name, "..") == 0) continue;

            if (s_modCount >= MAX_MODS) break;
            ModEntry *mod = &s_mods[s_modCount];
            memset(mod, 0, sizeof(*mod));

            strncpy(mod->id, fileinfo.name, sizeof(mod->id) - 1);
            snprintf(mod->full_path, sizeof(mod->full_path), "%s/%s", s_modsDir, fileinfo.name);

            if (!parse_mod_ini(mod->full_path, mod)) {
                /* Not a valid mod folder (missing mod.ini) */
                continue;
            }

            if (mod->name[0] == '\0') {
                strncpy(mod->name, mod->id, sizeof(mod->name) - 1);
            }

            /* Default include_dir to "files" if files/ subdir exists */
            if (mod->include_dir[0] == '\0') {
                char test_files[MAX_MOD_PATH];
                snprintf(test_files, sizeof(test_files), "%s/files", mod->full_path);
                if (is_directory(test_files)) {
                    strncpy(mod->include_dir, "files", sizeof(mod->include_dir) - 1);
                }
            }

            /* Check enabled status */
            if (has_config) {
                mod->enabled = 0;
                for (int a = 0; a < active_count; a++) {
                    if (strcmp(active_ids[a], mod->id) == 0) {
                        mod->enabled = 1;
                        break;
                    }
                }
            } else {
                mod->enabled = 1;
            }

            s_modCount++;
        } while (_findnext(handle, &fileinfo) == 0);
        _findclose(handle);
    }
#else
    DIR *d = opendir(s_modsDir);
    if (d) {
        struct dirent *ent;
        while ((ent = readdir(d)) != NULL) {
            if (strcmp(ent->d_name, ".") == 0 || strcmp(ent->d_name, "..") == 0) {
                continue;
            }

            char sub_path[MAX_MOD_PATH];
            snprintf(sub_path, sizeof(sub_path), "%s/%s", s_modsDir, ent->d_name);
            if (!is_directory(sub_path)) {
                continue;
            }

            if (s_modCount >= MAX_MODS) break;
            ModEntry *mod = &s_mods[s_modCount];
            memset(mod, 0, sizeof(*mod));

            strncpy(mod->id, ent->d_name, sizeof(mod->id) - 1);
            strncpy(mod->full_path, sub_path, sizeof(mod->full_path) - 1);

            if (!parse_mod_ini(mod->full_path, mod)) {
                /* Not a valid mod folder (missing mod.ini) */
                continue;
            }

            if (mod->name[0] == '\0') {
                strncpy(mod->name, mod->id, sizeof(mod->name) - 1);
            }

            /* Default include_dir to "files" if files/ subdir exists */
            if (mod->include_dir[0] == '\0') {
                char test_files[MAX_MOD_PATH];
                snprintf(test_files, sizeof(test_files), "%s/files", mod->full_path);
                if (is_directory(test_files)) {
                    strncpy(mod->include_dir, "files", sizeof(mod->include_dir) - 1);
                }
            }

            /* Check enabled status */
            if (has_config) {
                mod->enabled = 0;
                for (int a = 0; a < active_count; a++) {
                    if (strcmp(active_ids[a], mod->id) == 0) {
                        mod->enabled = 1;
                        break;
                    }
                }
            } else {
                mod->enabled = 1;
            }

            s_modCount++;
        }
        closedir(d);
    }
#endif

    /* Reorder enabled mods if active_mods list specified explicit priority */
    if (has_config && active_count > 1) {
        ModEntry ordered[MAX_MODS];
        int ordCount = 0;

        for (int a = 0; a < active_count; a++) {
            for (int m = 0; m < s_modCount; m++) {
                if (s_mods[m].enabled && strcmp(s_mods[m].id, active_ids[a]) == 0) {
                    ordered[ordCount++] = s_mods[m];
                    s_mods[m].enabled = -1; /* marked placed */
                    break;
                }
            }
        }
        for (int m = 0; m < s_modCount; m++) {
            if (s_mods[m].enabled != -1) {
                ordered[ordCount++] = s_mods[m];
            } else {
                s_mods[m].enabled = 1;
            }
        }
        for (int m = 0; m < ordCount; m++) {
            s_mods[m] = ordered[m];
            if (s_mods[m].enabled == -1) s_mods[m].enabled = 1;
        }
    }

    /* Compute g_modDisableBlink */
    if (g_modsEnabled) {
        for (int i = 0; i < s_modCount; i++) {
            if (s_mods[i].enabled && s_mods[i].disable_blink) {
                g_modDisableBlink = 1;
                break;
            }
        }
    }

    SDL_Log("VFS: Loaded %d mods (modsEnabled=%d, disableBlink=%d)",
            s_modCount, g_modsEnabled, g_modDisableBlink);
    for (int i = 0; i < s_modCount; i++) {
        SDL_Log("VFS: [%c] Mod #%d: '%s' (v%s by %s, dir='%s', blink=%d)",
                s_mods[i].enabled ? 'X' : ' ',
                i + 1, s_mods[i].name, s_mods[i].version, s_mods[i].author,
                s_mods[i].include_dir, s_mods[i].disable_blink);
    }
}

int VFS_ResolvePath(const char *rel_path, char *out_path, size_t out_len)
{
    if (!rel_path || !*rel_path || !out_path || out_len == 0) {
        return 0;
    }

    /* If not initialized, attempt lazy init */
    if (!s_initialized) {
        VFS_Init(s_basePath);
    }

    if (!g_modsEnabled || s_modCount == 0) {
        return 0;
    }

    /* Normalize backslashes to forward slashes and strip leading "./" or "/" */
    char norm_rel[MAX_MOD_PATH];
    size_t rl = strlen(rel_path);
    if (rl >= sizeof(norm_rel)) {
        return 0;
    }
    for (size_t i = 0; i <= rl; i++) {
        norm_rel[i] = (rel_path[i] == '\\') ? '/' : rel_path[i];
    }
    const char *clean_rel = norm_rel;
    while (*clean_rel == '/' || (*clean_rel == '.' && *(clean_rel + 1) == '/')) {
        if (*clean_rel == '/') clean_rel++;
        else clean_rel += 2;
    }

    if (*clean_rel == '\0') {
        return 0;
    }

    /* Check each active enabled mod */
    for (int i = 0; i < s_modCount; i++) {
        if (!s_mods[i].enabled) {
            continue;
        }

        const ModEntry *mod = &s_mods[i];

        /* Layout 1: mod->full_path / mod->include_dir / clean_rel */
        if (mod->include_dir[0] != '\0') {
            char candidate_dir[MAX_MOD_PATH];
            snprintf(candidate_dir, sizeof(candidate_dir), "%s/%s", mod->full_path, mod->include_dir);
            if (vfs_find_case_insensitive(candidate_dir, clean_rel, out_path, out_len)) {
                return 1;
            }
        }

        /* Layout 2: mod->full_path / files / clean_rel */
        if (sr_strcasecmp(mod->include_dir, "files") != 0) {
            char candidate_dir[MAX_MOD_PATH];
            snprintf(candidate_dir, sizeof(candidate_dir), "%s/files", mod->full_path);
            if (vfs_find_case_insensitive(candidate_dir, clean_rel, out_path, out_len)) {
                return 1;
            }
        }

        /* Layout 3: mod->full_path / clean_rel */
        if (vfs_find_case_insensitive(mod->full_path, clean_rel, out_path, out_len)) {
            return 1;
        }
    }

    return 0;
}

int VFS_GetDisableBlink(void)
{
    return (g_modsEnabled && g_modDisableBlink);
}

int VFS_GetModCount(void)
{
    return s_modCount;
}

const ModEntry *VFS_GetModEntry(int index)
{
    if (index < 0 || index >= s_modCount) {
        return NULL;
    }
    return &s_mods[index];
}
