// ============================================================
//  [SIMULATION] SIM MODES
//  Not part of core logic
// ============================================================
#include "includes.h"
#include "config.h"
#include "sim_modes.h"

extern float S1, S2, S3, S4;
extern void process_sensors(void);


// ============================================================
//  [SIMULATION] SIM_MODE 1 — Large object approaching then receding
//  All four sensors detect the same distance simultaneously
// ============================================================

void test_large_object() {
    float approach[5] = {5.5, 4.5, 3.5, 2.5, 1.0};
    float recede[5]   = {1.0, 2.5, 3.5, 4.5, 5.5};
    int tier_percents[5] = {20, 40, 60, 80, 100};
    int i;

    printf("\n--- LARGE OBJECT TEST | Object approaching ---\n");
    for (i = 0; i < 5; i++) {
        S1 = approach[i];
        S2 = approach[i];
        S3 = approach[i];
        S4 = approach[i];
        printf("\n--- Tier %d | %d%% | %.1fft ---\n",
               5-i, tier_percents[i], approach[i]);
        process_sensors();
        vTaskDelay(pdMS_TO_TICKS(3000));
    }

    printf("\n--- LARGE OBJECT TEST | Object receding ---\n");
    for (i = 1; i < 5; i++) {
        S1 = recede[i];
        S2 = recede[i];
        S3 = recede[i];
        S4 = recede[i];
        printf("\n--- Tier %d | %d%% | %.1fft ---\n",
               i+1, tier_percents[i], recede[i]);
        process_sensors();
        vTaskDelay(pdMS_TO_TICKS(3000));
    }

    S1 = 0.0; S2 = 0.0; S3 = 0.0; S4 = 0.0;
    process_sensors();
    printf("\n--- LARGE OBJECT TEST | Complete ---\n");
}


/* ============================================================
    [SIMULATION] SIM_MODE 2 — Moving object arc
    Object moves left to right then right to left once then stops
    Distance follows an arc — far on outside, closest at center
    Overlap zones use matching distances (same tier) for realistic handoff

    Arc distances:
    S1 alone      = 5.5ft     (T5, 20%)
    S1+S2 overlap = 4.0/4.0ft (T3, 60% — same tier, fire together)
    S2 alone      = 2.5ft     (T2, 80%)
    S2+S3 overlap = 2.0/2.0ft (T2, 80% — center, closest, fire together)
    S3 alone      = 2.5ft     (T2, 80%)
    S3+S4 overlap = 4.0/4.0ft (T3, 60% — same tier, fire together)
    S4 alone      = 5.5ft     (T5, 20%)
============================================================== */

void test_moving_arc() {
    printf("\n--- MOVING ARC TEST | Object moving left to right then back ---\n");

    printf("\n--- LEFT TO RIGHT ---\n");

    S1 = 5.5; S2 = 0.0; S3 = 0.0; S4 = 0.0;
    process_sensors();
    vTaskDelay(pdMS_TO_TICKS(3000));

    S1 = 4.0; S2 = 4.0; S3 = 0.0; S4 = 0.0;
    process_sensors();
    vTaskDelay(pdMS_TO_TICKS(3000));

    S1 = 0.0; S2 = 2.5; S3 = 0.0; S4 = 0.0;
    process_sensors();
    vTaskDelay(pdMS_TO_TICKS(3000));

    S1 = 0.0; S2 = 2.0; S3 = 2.0; S4 = 0.0;
    process_sensors();
    vTaskDelay(pdMS_TO_TICKS(3000));

    S1 = 0.0; S2 = 0.0; S3 = 2.5; S4 = 0.0;
    process_sensors();
    vTaskDelay(pdMS_TO_TICKS(3000));

    S1 = 0.0; S2 = 0.0; S3 = 4.0; S4 = 4.0;
    process_sensors();
    vTaskDelay(pdMS_TO_TICKS(3000));

    S1 = 0.0; S2 = 0.0; S3 = 0.0; S4 = 5.5;
    process_sensors();
    vTaskDelay(pdMS_TO_TICKS(3000));

    printf("\n--- RIGHT TO LEFT ---\n");

    S1 = 0.0; S2 = 0.0; S3 = 0.0; S4 = 5.5;
    process_sensors();
    vTaskDelay(pdMS_TO_TICKS(3000));

    S1 = 0.0; S2 = 0.0; S3 = 4.0; S4 = 4.0;
    process_sensors();
    vTaskDelay(pdMS_TO_TICKS(3000));

    S1 = 0.0; S2 = 0.0; S3 = 2.5; S4 = 0.0;
    process_sensors();
    vTaskDelay(pdMS_TO_TICKS(3000));

    S1 = 0.0; S2 = 2.0; S3 = 2.0; S4 = 0.0;
    process_sensors();
    vTaskDelay(pdMS_TO_TICKS(3000));

    S1 = 0.0; S2 = 2.5; S3 = 0.0; S4 = 0.0;
    process_sensors();
    vTaskDelay(pdMS_TO_TICKS(3000));

    S1 = 4.0; S2 = 4.0; S3 = 0.0; S4 = 0.0;
    process_sensors();
    vTaskDelay(pdMS_TO_TICKS(3000));

    S1 = 5.5; S2 = 0.0; S3 = 0.0; S4 = 0.0;
    process_sensors();
    vTaskDelay(pdMS_TO_TICKS(3000));

    S1 = 0.0; S2 = 0.0; S3 = 0.0; S4 = 0.0;
    process_sensors();
    printf("\n--- MOVING ARC TEST | Complete ---\n");
}