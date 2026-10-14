# The most recent test result since this directive began must have passed. Results are not
# paired with "say poll" markers: Paper logs commands and their results out of order, so a
# marker can come before or after its own result. Other log lines between them are ignored.
NR <= begin { next }
/Test (passed|failed)(, count: [0-9]+)?$/ { results++; passed = /Test passed/ }
END { exit !(results > 0 && passed) }
