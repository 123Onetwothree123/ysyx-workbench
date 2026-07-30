#!/bin/bash
sed 's/"lkc\.h"/"lkc.hpp"/g; s/"expr\.h"/"expr.hpp"/g; s/"list\.h"/"list.hpp"/g; s/"lkc_proto\.h"/"lkc_proto.hpp"/g' "$1" > "$2"
