#include <SDL.h>
#include "dobby.h"
#include "sonicr_functions.h"

void (*orig_InitDefaultTimeTables)(void) = NULL;

void Custom_InitDefaultTimeTables(void) {
    // 1. Run the game's original setup logic first
    if (orig_InitDefaultTimeTables) {
        orig_InitDefaultTimeTables();
    }

    // 2. Overwrite the save states instantly right after inside RAM
    g_saveBlock[5] = 1;     // Unlocks Radiant Emerald
    g_saveBlock[10] = 2;    // Unlocks Dr. Robotnik
    g_saveBlock[11] = 2;    // Unlocks Metal Sonic
    g_saveBlock[12] = 2;    // Unlocks Tails Doll
    g_saveBlock[13] = 2;    // Unlocks Metal Knuckles
    g_saveBlock[14] = 2;    // Unlocks Egg Robo
    g_saveBlock[15] = 2;    // Unlocks Super Sonic

    SDL_Log("VFS [Dobby Mod]: 100%% Completion Save patch successfully injected!");
}

void sonic_mod_init(void) {
    SDL_Log("VFS [Dobby Mod]: Mod waking up. Hooking target assembly address...");
    
    // Dynamically patch the function address in device memory
    DobbyHook((void*)&InitDefaultTimeTables, 
              (void*)Custom_InitDefaultTimeTables, 
              (void**)&orig_InitDefaultTimeTables);
}
