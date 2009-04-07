package info.papyri.tests;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.*;
import org.apache.lucene.search.*;
import org.apache.lucene.search.spans.*;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.store.RAMDirectory;
import org.xml.sax.InputSource;
import org.xml.sax.XMLReader;

import info.papyri.epiduke.lucene.BigramIndexer;
import info.papyri.epiduke.lucene.Indexer;
import info.papyri.epiduke.lucene.analysis.AncientGreekAnalyzer;
import info.papyri.epiduke.lucene.spans.*;
import info.papyri.epiduke.sax.TEIHandler;
import info.papyri.epiduke.sax.TEILineHandler;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
public class WildcardHPROF {
    protected static final Term LINE_SPAN_TEMPLATE = new Term(Indexer.LINE_SPAN_TERM,"");
    protected static final Term WORD_SPAN_TEMPLATE = new Term(Indexer.WORD_SPAN_TERM,"");
    protected static final Term WORD_DF_TEMPLATE = new Term(Indexer.WORD_SPAN_TERM_DF,"");
    protected static final Term WORD_FL_TEMPLATE = new Term(Indexer.WORD_SPAN_TERM_FL,"");
    static final AncientGreekAnalyzer LINE_SPAN_ANALYZER = new AncientGreekAnalyzer();
    static final AncientGreekAnalyzer WORD_SPAN_ANALYZER = new AncientGreekAnalyzer();
    static final AncientGreekAnalyzer LINE_SPAN_ANALYZER_LC = new AncientGreekAnalyzer(AncientGreekAnalyzer.Normalize.CASE);
    static final AncientGreekAnalyzer WORD_SPAN_ANALYZER_LC = new AncientGreekAnalyzer(AncientGreekAnalyzer.Normalize.CASE);
    static final AncientGreekAnalyzer LINE_SPAN_ANALYZER_DF = new AncientGreekAnalyzer(AncientGreekAnalyzer.Normalize.DIACRITICS);
    static final AncientGreekAnalyzer WORD_SPAN_ANALYZER_DF = new AncientGreekAnalyzer(AncientGreekAnalyzer.Normalize.DIACRITICS);
    static final AncientGreekAnalyzer LINE_SPAN_ANALYZER_FL = new AncientGreekAnalyzer(AncientGreekAnalyzer.Normalize.CASE_AND_DIACRITICS);
    static final AncientGreekAnalyzer WORD_SPAN_ANALYZER_FL = new AncientGreekAnalyzer(AncientGreekAnalyzer.Normalize.CASE_AND_DIACRITICS);
    static Term [] substrings = new Term[]{WORD_FL_TEMPLATE.createTerm("και"),WORD_FL_TEMPLATE.createTerm("χε"),WORD_FL_TEMPLATE.createTerm("υπο"),WORD_FL_TEMPLATE.createTerm("ωλου")};
    static IndexSearcher check;
    static final Analyzer pfa = GreekTestsBase.getPFA();
    static java.util.Properties props;
    static IndexSearcher bigrams;
    static IndexSearcher bigramsDF;
    static IndexSearcher bigramsLC;
    static IndexSearcher bigramsFL;
    public static void main(String[] args) throws Exception {
        setUp();
        System.out.println("testWildcardHPROF");
        BooleanQuery.setMaxClauseCount(32*BooleanQuery.getMaxClauseCount());
        for(int i=0;i<50;i++){
//            SubstringSpanTermQuery kai = new SubstringSpanTermQuery(substrings[0],bigramsFL);
//            check.search(kai);
//            System.out.print('.');
            Scorer s = null;
            Term wc1 = substrings[1].createTerm("*" + substrings[1].text() + "*");
            Term wc2 = substrings[1].createTerm("*" + substrings[0].text() + "*");
            Term wc3 = substrings[1].createTerm("*" + substrings[2].text() + "*");
            WildcardQuery xe = new WildcardQuery(wc1);
            s = xe.weight(check).scorer(check.getIndexReader());
            while(s.next())s.doc();
//            check.search(xe);
            System.out.print('.');
//            SubstringSpanTermQuery upo = new SubstringSpanTermQuery(substrings[2],bigramsFL);
//            check.search(upo);
//            System.out.print('.');
//            SubstringSpanTermQuery wlou = new SubstringSpanTermQuery(substrings[3],bigramsFL);
//            check.search(wlou);
//            System.out.print('.');
//            WildcardQuery kai2 = new WildcardQuery(wc2);
//            WildcardQuery upo2 = new WildcardQuery(wc3);
            SubstringPhraseQuery kai_upo = new SubstringPhraseQuery();
            kai_upo.add(wc2);
            kai_upo.add(wc3);
            s = kai_upo.weight(check).scorer(check.getIndexReader());
            while(s.next())s.doc();
//            check.search(kai_upo);
            System.out.print('.');
            System.out.print(i);
            if((i+1)%10==0)System.out.println();
        }
    }
    
    static IndexSearcher getLocal() throws Exception {
        TEIHandler main = new TEILineHandler(true);
        main.addLineBreakTag("lb");
        main.addTextPattern("TEI.2/text/body/div");
        XMLReader digest = GreekTestsBase.createXMLReader();
        digest.setContentHandler(main);

        Directory dir = new RAMDirectory();
        //dir = FSDirectory.getDirectory("test",true);
        IndexWriter iWrite = new IndexWriter(dir,pfa);

        Document doc = GreekTestsBase.loadDocument(digest,"p.col.3.2.xml");
        iWrite.addDocument(doc);

        doc = GreekTestsBase.loadDocument(digest,"p.col.4.92.xml");
        iWrite.addDocument(doc);

        doc = GreekTestsBase.loadDocument(digest,"p.col.1.Inv480.xml");
        iWrite.addDocument(doc);

        doc = GreekTestsBase.loadDocument(digest,"p.mich.1.28.xml");
        iWrite.addDocument(doc);

        doc = GreekTestsBase.loadDocument(digest,"p.mich.1.42.xml");
        iWrite.addDocument(doc);

        doc = GreekTestsBase.loadDocument(digest,"p.mich.1.91.xml");
        iWrite.addDocument(doc);

        doc = GreekTestsBase.loadDocument(digest,"p.mich.1.100.xml");
        iWrite.addDocument(doc);

        doc = GreekTestsBase.loadDocument(digest,"p.mich.5.295.xml");
        iWrite.addDocument(doc);

        doc = GreekTestsBase.loadDocument(digest,"p.col.8.227.xml");
        iWrite.addDocument(doc);

        doc = GreekTestsBase.loadDocument(digest,"p.col.11.300.xml");
        iWrite.addDocument(doc);

        doc = GreekTestsBase.loadDocument(digest,"p.col.10.249.xml");
        iWrite.addDocument(doc);

        doc = GreekTestsBase.loadDocument(digest,"p.col.10.255.xml");
        iWrite.addDocument(doc);

        doc = GreekTestsBase.loadDocument(digest,"bgu.2.619.xml");
        iWrite.addDocument(doc);

        iWrite.optimize();
        IndexReader iRead = IndexReader.open(dir);
        return new IndexSearcher(iRead);
        }
    
    static  void setUp() throws Exception {

        props = new java.util.Properties();
        props.load(GreekFullTermTest.class.getResourceAsStream("/index.properties"));
        FSDirectory.setDisableLocks(true);
        Directory checkDir = FSDirectory.getDirectory(new File(props.getProperty("index.root")));
        check = new IndexSearcher(IndexReader.open(checkDir));
        
        File bigramRoot = new File(props.getProperty("bigram.root"));

            IndexReader plain =IndexReader.open(new RAMDirectory(FSDirectory.getDirectory(new File(bigramRoot,"plain"))));
            IndexReader df =IndexReader.open(new RAMDirectory(FSDirectory.getDirectory(new File(bigramRoot,"df"))));
            IndexReader lc =IndexReader.open(new RAMDirectory(FSDirectory.getDirectory(new File(bigramRoot,"lc"))));
            IndexReader fl =IndexReader.open(new RAMDirectory(FSDirectory.getDirectory(new File(bigramRoot,"fl"))));
            bigrams = new IndexSearcher(plain);
            bigramsDF = new IndexSearcher(df);
            bigramsLC = new IndexSearcher(lc);
            bigramsFL = new IndexSearcher(fl);
            Collection fields = check.getIndexReader().getFieldNames(IndexReader.FieldOption.ALL);
            for(Object field:fields){
                System.out.print(field.toString() + ", ");
            }
            System.out.println();
    }
    }
