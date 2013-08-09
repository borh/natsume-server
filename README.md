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

### Free-floating jars

Unfortunately, the following free-floating jars need to be downloaded and installed into a local maven repo:

```bash
mvn install:install-file -DgroupId=com.aliasi -DartifactId=lingpipe -Dpackaging=jar -Dversion=4.1.0 -Dfile=lingpipe-4.1.0.jar -DgeneratePom=true
```

```bash
mvn install:install-file -DgroupId=org.chasen -DartifactId=cabocha -Dpackaging=jar -Dversion=0.66 -Dfile=/usr/share/java/cabocha/CaboCha.jar -DgeneratePom=true
```

## Usage

Currently corpus input and processing is functional, but collocation extraction (the main feature) is missing, so at least for the near future, natsume-server is only interesting for the developer.

### Running with lein

The simplest way to run natsume-server is to use [lein](https://github.com/technomancy/leiningen) in the project directory.

```bash
lein run /path/to/corpus/dir/
```

## Planned Features

### Integration with automatic error correction/feedback system Nutmeg

- provide an API from Natsume to Nutmeg to ease register (genre) error detection
    - at the token level
    - at the collocation level
    - for both levels include the option of sending back example sentences with query included (this should also be offered as a separate API)
- provide fulltext search API (perhaps using Elastic Search ([Elastisch](https://github.com/clojurewerkz/elastisch) in Clojure); maybe even generalize it so that register error detection could be possible)

### Integration with reading assistance system Asunaro

- add functionality to help read example sentences in Natsume
    - tree view or dependency link view in JavaScript (by translating the existing Java program; or from scratch for the dependency view)

### Learner dashboard

- provide a dashboard displaying everything the user did, using cookies and totally optional (make sure to save old Apache logs!)
    - need to record all user input and clicks (an expansion of the users/history tables in Asunaro)
    - provide a user login system (OAuth?/standard)
- provide review/learning tasks based on users input and level
    - help build up vocabulary by using cloze test-like quizzes
    - integrate with Natane learner database to train against common errors (per native language)

## Roadmap

0.2.0 - initial release

0.3.0 - basic collocation extraction

0.4.0 - token and collocation information offered as API

0.6.0 - pattern matching and transformation rule engine/state machine inspired by CQL/MQL

...

1.0.0 - working release ready to replace current main site

## License

Copyright © 2012, 2013 Bor Hodošček

Distributed under the Eclipse Public License, the same as Clojure.
