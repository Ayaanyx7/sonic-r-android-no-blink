#ifndef SONICR_FUNCTIONS_MOD_H
#define SONICR_FUNCTIONS_MOD_H

// Tells the compiler that the swappable function pointer variable lives in the main engine
extern void (*InitDefaultTimeTables)(void);

// Tells the compiler that the global save block array lives in the main engine
extern int g_saveBlock[554];

#endif
