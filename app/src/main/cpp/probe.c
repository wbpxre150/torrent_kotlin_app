#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>
#include <signal.h>
#include <string.h>

static volatile sig_atomic_t running = 1;

static void handle_sigterm(int sig) { running = 0; }

int main(int argc, char **argv) {
    // --version: print version + PID and exit 0 (used for build verification)
    if (argc == 2 && strcmp(argv[1], "--version") == 0) {
        printf("probe v1.0.0 pid=%ld\n", (long)getpid());
        fflush(stdout);
        return 0;
    }

    // Normal daemon mode: print startup line, then sleep forever
    signal(SIGTERM, handle_sigterm);
    printf("probe started pid=%ld\n", (long)getpid());
    fflush(stdout);

    while (running) {
        sleep(1);
    }

    printf("probe stopping\n");
    fflush(stdout);
    return 0;
}
