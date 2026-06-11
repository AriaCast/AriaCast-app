/*
 * aria-companion — Bluetooth A2DP → TCP PCM streamer
 *
 * Polls bluealsa for a connected A2DP device, opens the corresponding ALSA
 * capture PCM, and streams raw interleaved PCM frames to every connected TCP
 * client on TCP_PORT.
 *
 * Audio format: 44100 Hz, 2 ch, S16_LE  (~176 KB/s)
 * AriaCast resamples to 48 kHz internally.
 *
 * Build (done by post-build.sh via the Buildroot cross-compiler):
 *   ${CROSS}gcc -O2 -o aria-companion aria-companion.c -lasound
 */
#include <alsa/asoundlib.h>
#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#define TCP_PORT      7001
#define MAX_CLIENTS   8
#define RATE          44100
#define CHANNELS      2
#define FORMAT        SND_PCM_FORMAT_S16_LE
#define PERIOD_FRAMES 1024                         /* ~23 ms */
#define PERIOD_BYTES  (PERIOD_FRAMES * CHANNELS * 2)

static int    clients[MAX_CLIENTS];
static int    nclients = 0;
static int8_t pcmbuf[PERIOD_BYTES];

/* ── TCP helpers ─────────────────────────────────────────────────────────── */

static void setnonblock(int fd) {
    fcntl(fd, F_SETFL, fcntl(fd, F_GETFL, 0) | O_NONBLOCK);
}

static void client_add(int fd) {
    if (nclients >= MAX_CLIENTS) { close(fd); return; }
    int one = 1;
    setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, &one, sizeof(one));
    setnonblock(fd);
    clients[nclients++] = fd;
    fprintf(stderr, "aria: client connected  (%d/%d)\n", nclients, MAX_CLIENTS);
}

static void client_drop(int i) {
    close(clients[i]);
    clients[i] = clients[--nclients];
    fprintf(stderr, "aria: client dropped    (%d/%d)\n", nclients, MAX_CLIENTS);
}

static void broadcast(const void *buf, ssize_t len) {
    for (int i = nclients - 1; i >= 0; i--)
        if (send(clients[i], buf, len, MSG_NOSIGNAL) < 0)
            client_drop(i);
}

static int make_tcp_server(void) {
    int fd = socket(AF_INET, SOCK_STREAM, 0);
    int one = 1;
    setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &one, sizeof(one));
    setnonblock(fd);
    struct sockaddr_in a = {
        .sin_family      = AF_INET,
        .sin_port        = htons(TCP_PORT),
        .sin_addr.s_addr = INADDR_ANY,
    };
    if (bind(fd, (struct sockaddr *)&a, sizeof(a)) < 0 ||
        listen(fd, 8) < 0) {
        perror("bind/listen"); exit(1);
    }
    return fd;
}

/* ── bluealsa device detection ───────────────────────────────────────────── */

/*
 * Parse output of `bluealsa-cli list-pcms` to get the ALSA device string.
 * A line looks like:
 *   /org/bluealsa/hci0/dev_AA_BB_CC_DD_EE_FF/a2dpsnk/sink
 */
static int find_device(char *out, size_t outsz) {
    FILE *fp = popen("bluealsa-cli list-pcms 2>/dev/null", "r");
    if (!fp) return 0;

    char line[256];
    int  found = 0;
    while (!found && fgets(line, sizeof(line), fp)) {
        if (!strstr(line, "/a2dpsnk/") && !strstr(line, "/a2dpsrc/"))
            continue;
        char *p = strstr(line, "dev_");
        if (!p) continue;

        /* dev_AA_BB_CC_DD_EE_FF → AA:BB:CC:DD:EE:FF */
        p += 4;
        char mac[18];
        for (int i = 0; i < 6; i++) {
            mac[i * 3]     = p[0];
            mac[i * 3 + 1] = p[1];
            mac[i * 3 + 2] = (i < 5) ? ':' : '\0';
            p += (i < 5) ? 3 : 2;   /* skip the '_' separator */
        }
        snprintf(out, outsz,
            "bluealsa:DEV=%s,PROFILE=a2dp,SRV=org.bluealsa", mac);
        found = 1;
    }
    pclose(fp);
    return found;
}

/* ── ALSA helpers ────────────────────────────────────────────────────────── */

static snd_pcm_t *open_pcm(const char *dev) {
    snd_pcm_t *pcm;
    if (snd_pcm_open(&pcm, dev, SND_PCM_STREAM_CAPTURE, 0) < 0)
        return NULL;

    snd_pcm_hw_params_t *hw;
    snd_pcm_hw_params_alloca(&hw);
    snd_pcm_hw_params_any(pcm, hw);
    snd_pcm_hw_params_set_access(pcm, hw, SND_PCM_ACCESS_RW_INTERLEAVED);
    snd_pcm_hw_params_set_format(pcm, hw, FORMAT);
    snd_pcm_hw_params_set_channels(pcm, hw, CHANNELS);
    unsigned int rate = RATE;
    snd_pcm_hw_params_set_rate_near(pcm, hw, &rate, 0);
    snd_pcm_uframes_t pf = PERIOD_FRAMES;
    snd_pcm_hw_params_set_period_size_near(pcm, hw, &pf, 0);

    if (snd_pcm_hw_params(pcm, hw) < 0) {
        snd_pcm_close(pcm); return NULL;
    }
    snd_pcm_start(pcm);
    return pcm;
}

/* ── main ────────────────────────────────────────────────────────────────── */

int main(void) {
    signal(SIGPIPE, SIG_IGN);

    int srv = make_tcp_server();
    fprintf(stderr, "aria-companion: listening on :%d\n", TCP_PORT);

    char       devname[128] = {0};
    snd_pcm_t *pcm          = NULL;

    for (;;) {
        /* Accept new TCP clients (non-blocking) */
        struct sockaddr_in ca; socklen_t cl = sizeof(ca);
        int cfd = accept(srv, (struct sockaddr *)&ca, &cl);
        if (cfd >= 0) client_add(cfd);

        /* Detect or re-open bluealsa PCM */
        if (!pcm) {
            char newdev[128];
            if (find_device(newdev, sizeof(newdev)) &&
                strcmp(newdev, devname) != 0) {
                strncpy(devname, newdev, sizeof(devname) - 1);
                pcm = open_pcm(devname);
                if (pcm)
                    fprintf(stderr, "aria: capturing %s\n", devname);
                else
                    fprintf(stderr, "aria: open failed: %s\n", devname);
            }
        }

        /* Read one period and broadcast */
        if (pcm) {
            snd_pcm_sframes_t n = snd_pcm_readi(pcm, pcmbuf, PERIOD_FRAMES);
            if (n > 0) {
                if (nclients) broadcast(pcmbuf, n * CHANNELS * 2);
            } else if (n == -EPIPE) {
                snd_pcm_prepare(pcm);
            } else if (n == -EAGAIN) {
                usleep(5000);
            } else {
                fprintf(stderr, "aria: ALSA error: %s — reopening\n",
                        snd_strerror((int)n));
                snd_pcm_close(pcm);
                pcm = NULL; devname[0] = '\0';
            }
        } else {
            usleep(200000);   /* poll every 200 ms when no BT source */
        }
    }
}
