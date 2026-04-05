// ============================================================
//  [SETUP] CONFIGURATION
// ============================================================

#ifndef HAPS_CONFIG_H
#define HAPS_CONFIG_H

// Hardware
#define NUM_HAPTICS  4
#define PWM_TIMER    LEDC_TIMER_0
#define PWM_MODE     LEDC_LOW_SPEED_MODE
#define PWM_RES      LEDC_TIMER_13_BIT
#define PWM_FREQ     5000
#define PWM_MAX      8191

// Distance settings (feet)
#define NO_READING   0.001

// Global variable declarations
extern int haptic_pins[NUM_HAPTICS];
extern float S1, S2, S3, S4;

// Controlled by bt_comms
extern bool haptics_On;

// Function declarations
void init_haptics(void);
void boot_haps(void);

#endif // HAPS_CONFIG_H