# natsume-server

Natsume writing assistance system data processor and server source code.

For those that wish to use the Natsume system, please go to the official page:

<http://hinoki.ryu.titech.ac.jp/natsume/>

## Prerequisites

`natsume-server` is written in [Clojure](http://clojure.org/) (1.4), and thus a working Java environment is required to run it.
All development is done on the IcedTea 7 JVM (OpenJDK), though others should work.

A PostgreSQL database is required as well.
Currently all testing is done on the new 9.2 release.

`natsume-server` additionally makes use of [MeCab](http://code.google.com/p/mecab/) and [CaboCha](https://code.google.com/p/cabocha/), though because of problems using the Java bindings, all CaboCha processing is done over the network.

## Usage

Currently corpus input and processing is functional, but collocation extraction (the main feature) is missing.

## Roadmap

0.2.0 - initial release
0.3.0 - basic collocation extraction

...

1.0.0 - working release ready to replace current main site

## License

Copyright © 2012 Bor Hodošček

Distributed under the Eclipse Public License, the same as Clojure.
