[![DOI](https://zenodo.org/badge/6169759.svg)](https://zenodo.org/badge/latestdoi/6169759)

# natsume-server

Natsume writing assistance system data processor and server source code.

For those that wish to use the Natsume system, please go to the official page:

<https://hinoki-project.org/natsume/>

## Developing

### Environment

To setup a development environment, start a REPL (with or without cider):

```sh
boot dev
boot cider dev
```

Run `go` to initiate and start the system.

```clojure
user=> (go)
:started
```

By default this creates a web server at <http://localhost:3000>.

When you make changes to your source files, use `reset` to reload any
modified files and restart the server.

```clojure
user=> (reset)
:reloading (...)
:started
```

## Prerequisites

`natsume-server` is written in [Clojure](http://clojure.org/), and thus requires a working Java environment to run.
All development is done on the IcedTea 8 JVM (OpenJDK), though other versions (7) should work.

A PostgreSQL database is required as well.
Currently all testing is done on the 9.5 release.

`natsume-server` additionally makes use of [MeCab](https://taku910.github.io/mecab/) and [CaboCha](https://taku910.github.io/cabocha/).

### Free-floating jars

Unfortunately, the following free-floating jars need to be downloaded and installed into a local maven repo:

```bash
mvn install:install-file -DgroupId=org.chasen -DartifactId=cabocha -Dpackaging=jar -Dversion=0.69 -Dfile=/usr/share/java/cabocha/CaboCha.jar -DgeneratePom=true
```

## Usage

### Running with lein

The simplest way to run natsume-server is to use [lein](https://github.com/technomancy/leiningen) in the project directory.

```bash
lein run /path/to/corpus/dir/
```

### Corpus processing: Extraction (for word embedding training etc.)

```bash
boot run --extract --unit suw --out corpus-file.tsv
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

Copyright © 2012-2018 Bor Hodošček

Distributed under the Eclipse Public License, the same as Clojure.
