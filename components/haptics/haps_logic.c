// ============================================================
//  [SETUP] INCLUDES
// ============================================================
#include "haps_includes.h"
#include "haps_config.h"

// ============================================================
//  [SETUP] GLOBAL VARIABLES
// ============================================================

// GPIO pins for each haptic motor
int haptic_pins[NUM_HAPTICS] = {18, 17, 16, 4};

// ============================================================
//  [CORE] TIER CALCULATION
//  Maps rounded sensor distance to intensity tier
//  Sensor values are rounded whole foot values: 2, 3, 4, 5, 6
// ============================================================

int get_tier(float dist) {
    if (dist <= NO_READING) return 0;
    if (dist <= 2.0) return 1;
    if (dist == 3.0) return 2;
    if (dist == 4.0) return 3;
    if (dist == 5.0) return 4;
    if (dist == 6.0) return 5;
    return 0;
}

// ============================================================
//  PULSE DELAY CALCULATION
//  Returns the off time in ms for each tier
//  T1 (2ft)  = 100ms on, 100ms off (fastest)
//  T2 (3ft)  = 100ms on, 300ms off
//  T3 (4ft)  = 100ms on, 500ms off
//  T4 (5ft)  = 100ms on, 700ms off
//  T5 (6ft)  = 100ms on, 900ms off (slowest)
// ============================================================

int get_off_delay(int tier) {
    if (tier == 1) return 100;
    if (tier == 2) return 300;
    if (tier == 3) return 500;
    if (tier == 4) return 700;
    if (tier == 5) return 900;
    return 0;
}

// ============================================================
//  HAPTIC MOTOR CONTROL
// ============================================================

void set_haptic(int motor, uint32_t duty) {
    ledc_set_duty(PWM_MODE, (ledc_channel_t)motor, duty);
    ledc_update_duty(PWM_MODE, (ledc_channel_t)motor);
}

// ============================================================
//  HAPTIC TASK - H1
//  Reads S1, pulses H1 at rate determined by tier
//  Runs as independent FreeRTOS task
// ============================================================

void haptic_task_1(void *pvParameters) {
    int tier;
    int off_delay;
    int consecutive = 0;
    bool active = false;
    while (1) {
        if (!hapticsOn) {
            set_haptic(0, 0);
            consecutive = 0;
            active = false;
            vTaskDelay(pdMS_TO_TICKS(100));
            continue;
        }
        tier = get_tier(S1);
        if (tier == 5) {
            consecutive++;
            if (consecutive >= 2) active = true;
        } else if (tier > 0) {
            consecutive = 0;
            active = true;
        } else {
            consecutive = 0;
            active = false;
        }
        if (!active) {
            set_haptic(0, 0);
            vTaskDelay(pdMS_TO_TICKS(50));
        } else {
            off_delay = get_off_delay(tier);
            set_haptic(0, PWM_MAX);
            vTaskDelay(pdMS_TO_TICKS(100));
            set_haptic(0, 0);
            vTaskDelay(pdMS_TO_TICKS(off_delay));
        }
    }
}

// ============================================================
//  [CORE] HAPTIC TASK - H2
//  Reads S2, pulses H2 at rate determined by tier
//  Runs as independent FreeRTOS task
// ============================================================

void haptic_task_2(void *pvParameters) {
    int tier;
    int off_delay;
    int consecutive = 0;
    bool active = false;
    while (1) {
        if (!hapticsOn) {
            set_haptic(1, 0);
            consecutive = 0;
            active = false;
            vTaskDelay(pdMS_TO_TICKS(100));
            continue;
        }
        tier = get_tier(S2);
        if (tier == 5) {
            consecutive++;
            if (consecutive >= 2) active = true;
        } else if (tier > 0) {
            consecutive = 0;
            active = true;
        } else {
            consecutive = 0;
            active = false;
        }
        if (!active) {
            set_haptic(1, 0);
            vTaskDelay(pdMS_TO_TICKS(50));
        } else {
            off_delay = get_off_delay(tier);
            set_haptic(1, PWM_MAX);
            vTaskDelay(pdMS_TO_TICKS(100));
            set_haptic(1, 0);
            vTaskDelay(pdMS_TO_TICKS(off_delay));
        }
    }
}

// ============================================================
//  HAPTIC TASK - H3
//  Reads S3, pulses H3 at rate determined by tier
//  Runs as independent FreeRTOS task
// ============================================================

void haptic_task_3(void *pvParameters) {
    int tier;
    int off_delay;
    int consecutive = 0;
    bool active = false;
    while (1) {
        if (!hapticsOn) {
            set_haptic(2, 0);
            consecutive = 0;
            active = false;
            vTaskDelay(pdMS_TO_TICKS(100));
            continue;
        }
        tier = get_tier(S3);
        if (tier == 5) {
            consecutive++;
            if (consecutive >= 2) active = true;
        } else if (tier > 0) {
            consecutive = 0;
            active = true;
        } else {
            consecutive = 0;
            active = false;
        }
        if (!active) {
            set_haptic(2, 0);
            vTaskDelay(pdMS_TO_TICKS(50));
        } else {
            off_delay = get_off_delay(tier);
            set_haptic(2, PWM_MAX);
            vTaskDelay(pdMS_TO_TICKS(100));
            set_haptic(2, 0);
            vTaskDelay(pdMS_TO_TICKS(off_delay));
        }
    }
}

// ============================================================
//  HAPTIC TASK - H4
//  Reads S4, pulses H4 at rate determined by tier
//  Runs as independent FreeRTOS task
// ============================================================

void haptic_task_4(void *pvParameters) {
    int tier;
    int off_delay;
    int consecutive = 0;
    bool active = false;
    while (1) {
        if (!hapticsOn) {
            set_haptic(3, 0);
            consecutive = 0;
            active = false;
            vTaskDelay(pdMS_TO_TICKS(100));
            continue;
        }
        tier = get_tier(S4);
        if (tier == 5) {
            consecutive++;
            if (consecutive >= 2) active = true;
        } else if (tier > 0) {
            consecutive = 0;
            active = true;
        } else {
            consecutive = 0;
            active = false;
        }
        if (!active) {
            set_haptic(3, 0);
            vTaskDelay(pdMS_TO_TICKS(50));
        } else {
            off_delay = get_off_delay(tier);
            set_haptic(3, PWM_MAX);
            vTaskDelay(pdMS_TO_TICKS(100));
            set_haptic(3, 0);
            vTaskDelay(pdMS_TO_TICKS(off_delay));
        }
    }
}


// ============================================================
//  HARDWARE INIT
//  Configures PWM timer and channels for all four haptic motors
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
//  MODULE ENTRY POINT
//  Initializes haptic hardware, starts FreeRTOS tasks for each motor
// ============================================================

void boot_haps(void) {
    init_haptics();

    printf("\n==========================================\n");
    printf("   ESP32 HAPTIC VEST - BVI PROJECT\n");
    printf("==========================================\n\n");

    xTaskCreate(haptic_task_1, "haptic_task_1", 2048, NULL, 5, NULL);
    xTaskCreate(haptic_task_2, "haptic_task_2", 2048, NULL, 5, NULL);
    xTaskCreate(haptic_task_3, "haptic_task_3", 2048, NULL, 5, NULL);
    xTaskCreate(haptic_task_4, "haptic_task_4", 2048, NULL, 5, NULL);
}