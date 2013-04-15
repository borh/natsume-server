(ns natsume-server.importers.bccwj-spec
  (:require [speclj.core :refer :all]
            [natsume-server.importers.bccwj :refer :all]))

(comment
  (xml->paragraph-sentences "/data/BCCWJ-2012-dvd1/C-XML/VARIABLE/OT/OT01_00002.xml")
  (xml->paragraph-sentences "/data/BCCWJ-2012-dvd1/M-XML/OT/OT01_00002.xml")
  (xml->paragraph-sentences "/data/BCCWJ-2012-dvd1/C-XML/VARIABLE/OM/OM11_00001.xml"))
