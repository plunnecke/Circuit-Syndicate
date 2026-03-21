// ============================================================
//  [SETUP] INCLUDES
// ============================================================
#include "includes.h"
#include "config.h"
#include "sim_modes.h"


// ============================================================
//  [SETUP] GLOBAL VARIABLES
// ============================================================

// GPIO pins for each haptic motor
int haptic_pins[NUM_HAPTICS] = {27, 14, 12, 13};

// Tracks current duty cycle for each haptic
uint32_t haptic_duties[NUM_HAPTICS] = {0, 0, 0, 0};

// Sensor distance readings in feet
// In manual mode set these values directly
// In sim_modes these are set automatically, using core logic
float S1 = 0.0;
float S2 = 0.0;
float S3 = 0.0;
float S4 = 0.0;


// ============================================================
//  [CORE] TIER AND DUTY CYCLE CALCULATIONS
// ============================================================

int get_tier(float dist) {
    if (dist <= NO_READING) return 0;
    if (dist < 2.0) return 1;
    if (dist < 3.0) return 2;
    if (dist < 4.0) return 3;
    if (dist < 5.0) return 4;
    if (dist < 6.0) return 5;
    return 0;
}

uint32_t get_duty(float dist) {
    int tier = get_tier(dist);
    if (tier == 1) return 8191;
    if (tier == 2) return 6552;
    if (tier == 3) return 4914;
    if (tier == 4) return 3276;
    if (tier == 5) return 1638;
    return 0;
}

float get_closer(float dist1, float dist2) {
    if (dist1 <= NO_READING) return dist2;
    if (dist2 <= NO_READING) return dist1;
    if (dist1 < dist2) {
        return dist1;
    } else {
        return dist2;
    }
}


// ============================================================
//  [CORE] HAPTIC MOTOR CONTROL
// ============================================================

void set_haptic(int motor, uint32_t duty) {
    haptic_duties[motor] = duty;
    ledc_set_duty(PWM_MODE, (ledc_channel_t)motor, duty);
    ledc_update_duty(PWM_MODE, (ledc_channel_t)motor);
}

void all_haptics_off() {
    int i;
    for (i = 0; i < NUM_HAPTICS; i++) {
        set_haptic(i, 0);
    }
}


// ============================================================
//  [CORE] SENSOR PAIR LOGIC
// ============================================================

void check_sensor_pair(float dist1, float dist2, int motor1, int motor2, const char* pair_name) {
    int sensor1_active = dist1 > NO_READING && get_tier(dist1) > 0;
    int sensor2_active = dist2 > NO_READING && get_tier(dist2) > 0;

    if (!sensor1_active && !sensor2_active) {
        return;
    }

    if (sensor1_active && !sensor2_active) {
        uint32_t duty = get_duty(dist1);
        set_haptic(motor1, duty);
        printf("%s: Sensor L only (%.2fft) | H%d ON\n", pair_name, dist1, motor1+1);
        return;
    }

    if (!sensor1_active && sensor2_active) {
        uint32_t duty = get_duty(dist2);
        set_haptic(motor2, duty);
        printf("%s: Sensor R only (%.2fft) | H%d ON\n", pair_name, dist2, motor2+1);
        return;
    }

    int tier1 = get_tier(dist1);
    int tier2 = get_tier(dist2);
    float closer = get_closer(dist1, dist2);
    float diff = fabsf(dist1 - dist2);

    if (tier1 == tier2) {
        uint32_t duty = get_duty(closer);
        set_haptic(motor1, duty);
        set_haptic(motor2, duty);
        printf("%s: Same tier (%d) | H%d+H%d together (closest: %.2fft)\n",
               pair_name, tier1, motor1+1, motor2+1, closer);
    } else if (diff <= BOUNDARY_RANGE) {
        uint32_t duty = get_duty(closer);
        set_haptic(motor1, duty);
        set_haptic(motor2, duty);
        printf("%s: Boundary condition (diff=%.2fft) | H%d+H%d together\n",
               pair_name, diff, motor1+1, motor2+1);
    } else {
        uint32_t duty1 = get_duty(dist1);
        uint32_t duty2 = get_duty(dist2);
        set_haptic(motor1, duty1);
        set_haptic(motor2, duty2);
        printf("%s: Different tiers (%d vs %d) | H%d ON, H%d ON\n",
               pair_name, tier1, tier2, motor1+1, motor2+1);
    }
}


// ============================================================
//  [CORE] MAIN SENSOR PROCESSING
// ============================================================

void process_sensors() {
    printf("\n--- SENSOR READINGS: S1=%.4f S2=%.4f S3=%.4f S4=%.4f (feet) ---\n",
           S1, S2, S3, S4);

    if (S1 <= NO_READING && S2 <= NO_READING &&
        S3 <= NO_READING && S4 <= NO_READING) {
        all_haptics_off();
        printf("No objects detected. All haptics off.\n");
        return;
    }

    all_haptics_off();

    check_sensor_pair(S1, S2, 0, 1, "S1+S2");
    check_sensor_pair(S2, S3, 1, 2, "S2+S3");
    check_sensor_pair(S3, S4, 2, 3, "S3+S4");
}


// ============================================================
//  [CORE] HARDWARE INIT
// ============================================================

void init_haptics() {
    ledc_timer_config_t timer_conf = {
        .speed_mode      = PWM_MODE,
        .timer_num       = PWM_TIMER,
        .duty_resolution = PWM_RES,
        .freq_hz         = PWM_FREQ,
        .clk_cfg         = LEDC_AUTO_CLK
    };
    ESP_ERROR_CHECK(ledc_timer_config(&timer_conf));

    int i;
    for (i = 0; i < NUM_HAPTICS; i++) {
        ledc_channel_config_t channel_conf = {
            .speed_mode = PWM_MODE,
            .channel    = (ledc_channel_t)i,
            .timer_sel  = PWM_TIMER,
            .intr_type  = LEDC_INTR_DISABLE,
            .gpio_num   = haptic_pins[i],
            .duty       = 0,
            .hpoint     = 0
        };
        ESP_ERROR_CHECK(ledc_channel_config(&channel_conf));
    }
}


// ============================================================
//  [CORE] MAIN ENTRY POINT
// ============================================================

void app_main(void) {
    init_haptics(); 
    // init_haptics -> process_sensors -> check_process_pair -> get_tier -> get_duty -> set_haptic

    printf("\n==========================================\n");
    printf("   ESP32 HAPTIC VEST - BVI PROJECT\n");
    printf("==========================================\n");

#if SIM_MODE == 2
    printf("   MODE: MOVING OBJECT ARC\n");
    printf("==========================================\n\n");
    test_moving_arc();

#elif SIM_MODE == 1
    printf("   MODE: LARGE OBJECT APPROACHING\n");
    printf("==========================================\n\n");
    //while (1) {
        test_large_object();
    //}

#else
    printf("   MODE: MANUAL (S1-S4)\n");
    printf("==========================================\n\n");
    while (1) {
        process_sensors();
        vTaskDelay(pdMS_TO_TICKS(50));
    }
#endif
}