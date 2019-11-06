#!/usr/bin/env bash
clojure -Aoutdated -a outdated -a web,webdev,test,uberjar,depstar,runner --resolve-virtual
clojure -Aoutdated -a outdated -a web,webdev,test,uberjar,depstar,runner
