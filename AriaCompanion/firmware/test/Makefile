# Host-side tests for the portable parts of the firmware: `make -C test`
CFLAGS = -std=c11 -Wall -Wextra -Werror -I../include -I../src
test: test_loopback
	./test_loopback
test_loopback: test_loopback.c ../src/loopback.c ../src/loopback.h ../include/aria_companion_proto.h
	$(CC) $(CFLAGS) test_loopback.c ../src/loopback.c -o $@
clean:
	rm -f test_loopback
.PHONY: test clean
