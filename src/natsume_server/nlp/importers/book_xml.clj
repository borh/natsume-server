(ns natsume-server.nlp.importers.book-xml)
;; package main
;;
;; import "fmt"
;; import "os"
;; import "io"
;; import "bufio"
;; import "encoding/xml"
;; import "strings"
;; import "path/filepath"
;; import "flag"
;; import "log"
;;
;; //import "runtime"
;;
;; type BookInfo struct {
;; 	Isbn     string `xml:"isbn"`
;; 	Title    string `xml:"title"`
;; 	Pubdate  string `xml:"pubdate"`
;; 	Author   string `xml:"author"`
;; 	Basename string
;; }
;;
;; func MangleDate(date string) string {
;; 	return date[0:4]
;; }
;;
;; func Parse(in io.Reader, out io.Writer) (BookInfo, error) {
;; 	decoder := xml.NewDecoder(in)
;;
;; 	bi := BookInfo{}
;; 	var err error
;; 	for t, _ := decoder.Token(); err == nil; t, err = decoder.Token() {
;; 		switch se := t.(type) {
;; 		case xml.StartElement:
;; 			if se.Name.Local == "bookinfo" {
;; 				decoder.DecodeElement(&bi, &se)
;; 				bi.Pubdate = MangleDate(bi.Pubdate)
;; 			}
;; 		case xml.EndElement:
;; 			if se.Name.Local == "title" || se.Name.Local == "para" {
;; 				fmt.Fprint(out, "\n")
;; 			}
;; 		case xml.CharData:
;; 			text := strings.TrimSpace(string(se))
;; 			if text != "" {
;; 				fmt.Fprintln(out, text)
;; 			}
;; 		}
;; 	}
;; 	return bi, err
;; }
;;
;; func FileNormalizeSplit(path string) (string, string, string) {
;; 	dir, fn := filepath.Split(filepath.Clean(path))
;; 	basename := strings.Split(fn, ".")[0]
;; 	ext := filepath.Ext(fn)
;; 	return dir, basename, ext
;; }
;;
;; func ConvertFile(path string) (BookInfo, error) {
;; 	dir, basename, ext := FileNormalizeSplit(path)
;; 	if ext != ".xml" {
;; 		log.Fatal(fmt.Sprintf("%q is not an XML file, aborting.", path))
;; 	}
;;
;; 	inFile, err := os.Open(path)
;; 	if err != nil {
;; 		log.Fatal(err)
;; 	}
;; 	defer inFile.Close()
;; 	inFileReader := bufio.NewReader(inFile)
;;
;; 	outFileName := fmt.Sprintf("%s%s%s", dir, basename, ".txt")
;; 	outFile, err := os.Create(outFileName)
;; 	if err != nil {
;; 		log.Fatal(err)
;; 	}
;; 	defer outFile.Close()
;; 	outFileWriter := bufio.NewWriter(outFile)
;; 	defer func() {
;; 		if err = outFileWriter.Flush(); err != nil {
;; 			log.Fatal(err)
;; 		}
;; 	}()
;;
;; 	log.Printf("%q -> %q\n", path, outFileName)
;;
;; 	bi, _ := Parse(inFileReader, outFileWriter)
;; 	bi.Basename = basename
;;
;; 	return bi, err
;; }
;;
;; func main() {
;; 	flag.Parse()
;;
;; 	sourcesFile, err := os.OpenFile("sources.tsv", os.O_RDWR|os.O_APPEND|os.O_CREATE, 0666)
;; 	if err != nil {
;; 		log.Fatal(err)
;; 	}
;; 	defer sourcesFile.Close()
;;
;; 	sourcesWriter := bufio.NewWriter(sourcesFile)
;; 	defer func() {
;; 		if err = sourcesWriter.Flush(); err != nil {
;; 			log.Fatal(err)
;; 		}
;; 	}()
;;
;; 	ch := make(chan BookInfo)
;; 	// Limit to batches of NumCPU()
;; 	// Check if above is the right place for a channel
;; 	//ncpu := runtime.NumCPU()
;; 	for _, file := range flag.Args() {
;; 		go func(file string) {
;; 			bi, err := ConvertFile(file)
;; 			if err != nil {
;; 				log.Print(err)
;; 			}
;; 			ch<- bi
;; 		}(file)
;; 	}
;;
;; 	for i := 0; i < len(flag.Args()); i++ {
;; 		bi := <-ch
;; 		fmt.Fprintf(sourcesWriter, "%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n", bi.Title, bi.Author, bi.Pubdate, bi.Basename, "岩波ジュニア新書", "岩波ジュニア新書", "岩波ジュニア新書", "岩波ジュニア新書")
;; 	}
;; }
