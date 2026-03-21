// ============================================================
//  [SETUP] CONFIGURATION
//  Move to: config/config.h
// ============================================================

#ifndef CONFIG_H
#define CONFIG_H

// Simulation scenario select
// 0 = Manual              
// 1 = Large object        
// 2 = Moving object arc   
#define SIM_MODE 0

// Hardware
#define NUM_HAPTICS  4
#define PWM_TIMER    LEDC_TIMER_0
#define PWM_MODE     LEDC_LOW_SPEED_MODE
#define PWM_RES      LEDC_TIMER_13_BIT
#define PWM_FREQ     5000
#define PWM_MAX      8191

// Distance settings (feet)
#define NO_READING      0.001
#define BOUNDARY_RANGE  0.1

// Global variable declarations (defined in main.c)
extern int haptic_pins[NUM_HAPTICS];
extern uint32_t haptic_duties[NUM_HAPTICS];
extern float S1, S2, S3, S4;

#endif // CONFIG_H