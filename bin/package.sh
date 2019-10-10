#!/usr/bin/env bash

rm -rf resources/public/js/cljs-runtime/
shadow-cljs -A:web release app
clj -Spom
clj -A:jvm:depstar
