#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"

// Module boot declarations
void boot_nv(void);
void boot_haps(void);
void boot_bt(void);

void app_main(void) {
    printf("\n===========================\n");
    printf("  BVI ENMASSE - SYSTEM STARTUP\n");
    printf("=============================\n\n");

    boot_nv();
    boot_bt();
    boot_haps();

    printf("All modules initialized.\n");    

}
