package org.apache.lucene.search.highlight;

import org.apache.lucene.search.highlight.PNSpanScorer;
import java.io.IOException;
import java.io.StringReader;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Vector;
import java.util.Iterator;
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.Token;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.util.PriorityQueue;
import org.apache.lucene.analysis.CachingTokenFilter;
import org.apache.lucene.search.Query;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.index.memory.MemoryIndex;
import org.apache.lucene.search.spans.SpanQuery;
import info.papyri.epiduke.lucene.analysis.CopyingTokenFilter;

import org.apache.log4j.Logger;

public abstract class HighlightUtil {

    /**
     * Making a pass at speeding the highlighting up.  In our case, almost all of the work can be done statically.
     */
    public static final int DEFAULT_MAX_CHARS_TO_ANALYZE = 75 * 1024;
    public static final int DEFAULT_MAX_DOC_BYTES_TO_ANALYZE = DEFAULT_MAX_CHARS_TO_ANALYZE;
    private static int maxDocCharsToAnalyze = DEFAULT_MAX_CHARS_TO_ANALYZE;
    private static final FastHTMLFormatter formatter = FastHTMLFormatter.THREADSAFE_FORMATTER;
    private static final BracketEncoder encoder = BracketEncoder.THREADSAFE_ENCODER;
    private static final Pattern LINE_TOKEN = Pattern.compile("&LINE-[A-Za-z0-9]+;");
    private static final Logger LOG = Logger.getLogger(HighlightUtil.class);
    private static final HashMap readers = new HashMap();

    public static final TextFragment[] getBestTextFragmentsNoGroup(
            CachingTokenFilter cache,
            String texty,
            SimpleFragmenter textFragmenter,
            PNSpanScorer fragmentScorer,
            boolean mergeContiguousFragments,
            int maxNumFragments)
            throws IOException {

        char[] textChars = texty.toCharArray();
        int probableNumFrags = (maxDocCharsToAnalyze / textFragmenter.getFragmentSize()) + ((maxDocCharsToAnalyze % textFragmenter.getFragmentSize() != 0) ? 1 : 0);
        BuilderTextFragment[] docFrags = new BuilderTextFragment[probableNumFrags];
        int docFragCtr = 0;
        StringBuilder newText = new StringBuilder(Math.min(maxDocCharsToAnalyze, textChars.length));
//        StringBuffer newText=new StringBuffer(Math.min(maxDocCharsToAnalyze, textChars.length));

        BuilderTextFragment currentFrag = new BuilderTextFragment(newText, newText.length(), docFrags.length);
        fragmentScorer.startFragment(currentFrag);
        docFrags[docFragCtr++] = currentFrag;


        try {
            Token token = new Token();
            Token hangingToken = null;
            char[] tokenText;
            int startOffset = 0;
            int endOffset = 0;
            int lastEndOffset = 0;
            float score = 0;
            textFragmenter.start(texty);

            thruMaxChars:
            while (((token = cache.next()) != null) && (token.startOffset() < maxDocCharsToAnalyze)) {
                if (textFragmenter.isNewFragment(token)) {
                    if (++docFragCtr == docFrags.length) {
                        break;
                    }
                    currentFrag.setScore(fragmentScorer.getFragmentScore());
                    //record stats for a new fragment
                    currentFrag.textEndPos = newText.length();
                    currentFrag = new BuilderTextFragment(newText, newText.length(), docFrags.length);
                    fragmentScorer.startFragment(currentFrag);
                    docFrags[docFragCtr] = currentFrag;
                }

                if (token.startOffset() >= endOffset) {
                    if (startOffset > lastEndOffset) {
                        if (BracketEncoder.test(textChars, lastEndOffset, startOffset)) {
                            newText.append(encoder.encodeText(textChars, lastEndOffset, startOffset));
                        } else {
                            newText.append(textChars, lastEndOffset, startOffset - lastEndOffset);
                        }
                    }


                    if (score > 0) {
                        tokenText = encoder.encodeText(textChars, startOffset, endOffset);
                        newText.append(formatter.highlightTerm(tokenText));
                    } else {
                        if (BracketEncoder.test(textChars, startOffset, startOffset)) {
                            newText.append(encoder.encodeText(textChars, startOffset, endOffset));
                        } else {
                            newText.append(textChars, startOffset, endOffset - startOffset);
                        }
                    }
                    score = fragmentScorer.getTokenScore(token);
                    lastEndOffset = Math.max(endOffset, lastEndOffset);
                    startOffset = token.startOffset();
                    endOffset = token.endOffset();
                    hangingToken = null;
                } else {
                    startOffset = Math.min(startOffset, token.startOffset());
                    endOffset = Math.max(endOffset, token.endOffset());
                    score += fragmentScorer.getTokenScore(token);
                    hangingToken = token;
                }

            } // thruMaxChars

            lastToken:
            if (hangingToken != null && docFragCtr < docFrags.length) {
                if (startOffset > lastEndOffset) {
                    if (BracketEncoder.test(textChars, lastEndOffset, startOffset)) {
                        newText.append(encoder.encodeText(textChars, lastEndOffset, startOffset));
                    } else {
                        newText.append(textChars, lastEndOffset, startOffset - lastEndOffset);
                    }
                }
                tokenText = encoder.encodeText(textChars, startOffset, endOffset);

                if (score > 0) {
                    newText.append(formatter.highlightTerm(tokenText));
                } else {
                    newText.append(tokenText);
                }
                lastEndOffset = Math.max(endOffset, lastEndOffset);
                currentFrag.setScore(fragmentScorer.getFragmentScore());
                //record stats for a new fragment
                currentFrag.textEndPos = newText.length();
                currentFrag = new BuilderTextFragment(newText, newText.length(), docFrags.length);
                fragmentScorer.startFragment(currentFrag);
                docFrags[docFragCtr] = currentFrag;
            }


            //Test what remains of the original text beyond the point where we stopped analyzing 
            if ( //                  if there is text beyond the last token considered..
                    (lastEndOffset < textChars.length) &&
                    //                  and that text is not too large...
                    (textChars.length < maxDocCharsToAnalyze)) {
                //append it to the last fragment
                newText.append(encoder.encodeText(textChars, lastEndOffset, textChars.length));
            }

            currentFrag.textEndPos = newText.length();

            //return the most relevant fragments
            BuilderTextFragment[] frag = getTopFromQueue(docFrags, maxNumFragments);

            //merge any contiguous fragments to improve readability
            if (mergeContiguousFragments) {
                int merged = mergeContiguousFragments(frag, cache, fragmentScorer);

                if (merged != frag.length) {
                    BuilderTextFragment[] fragTexts = new BuilderTextFragment[merged];
                    int mctr = 0;
                    for (int i = 0; i < frag.length; i++) {
                        if ((frag[i] != null) && (frag[i].getScore() > 0)) {
                            fragTexts[mctr++] = frag[i];
                        }
                    }
                    frag = fragTexts;
                }
            }

            return frag;

        } finally {
            if (cache != null) {
                try {
                    //tokenStream.close();
                } catch (Exception e) {
                }

            }
        }
    }

    public static final BuilderTextFragment[] getTextFragments(
            CachingTokenFilter tokenStream,
            final String texty,
            LineFragmenter textFragmenter,
            Scorer fragmentScorer)
            throws IOException {
        char[] textChars = texty.toCharArray();
        int probableFragSize = 0;
        Matcher match = LINE_TOKEN.matcher(texty);
        while (match.find() && match.end() < maxDocCharsToAnalyze) {
            probableFragSize++;
        }

        BuilderTextFragment[] docFrags = new BuilderTextFragment[probableFragSize];
        int docFragCtr = -1;
        StringBuilder newText = new StringBuilder(Math.min(maxDocCharsToAnalyze, textChars.length));

        BuilderTextFragment currentFrag = new BuilderTextFragment(newText, newText.length(), docFrags.length);
        fragmentScorer.startFragment(currentFrag);

        try {
            Token token = tokenStream.next();
            Token hangingToken = null;
            char[] tokenText;
            int startOffset = 0;
            int endOffset = 0;
            int lastEndOffset = 0;
            float score = 0;
            textFragmenter.start(texty);
            if (token == null) {
                System.err.println("No tokens for fragmenting");
                return new BuilderTextFragment[0];
            }
            thruMaxChars:
            do {
                startOffset = token.startOffset();
                endOffset = token.endOffset();
                if (startOffset > lastEndOffset) { // grab skipped text
                    if (BracketEncoder.test(textChars, lastEndOffset, startOffset)) {
                        newText.append(encoder.encodeText(textChars, lastEndOffset, startOffset));
                    } else {
                        newText.append(textChars, lastEndOffset, startOffset - lastEndOffset);
                    }
                    lastEndOffset = startOffset;
                }

                if (textFragmenter.isNewFragment(token)) {
                    currentFrag.setScore(fragmentScorer.getFragmentScore());
                    //record stats for a new fragment
                    currentFrag.textEndPos = newText.length();
                    docFragCtr++;
                    if (docFragCtr == docFrags.length) {
                        break;
                    }
                    docFrags[docFragCtr] = new BuilderTextFragment(newText, newText.length(), docFrags.length);
                    currentFrag = (BuilderTextFragment) docFrags[docFragCtr];
                    fragmentScorer.startFragment(currentFrag);
                } else {
                    if (docFragCtr == -1 && docFrags[0] == null) {
                        docFragCtr = 0;
                        docFrags[0] = currentFrag;
                    }
                }

                score = fragmentScorer.getTokenScore(token);
                if (currentFrag.first == null) {
                    currentFrag.first = token;
                    currentFrag.last = currentFrag.first;
                } else {
                    //currentFrag.last.next = token;
                    currentFrag.last = token;
                }

                if (token.startOffset() >= lastEndOffset) {

                    if (score > 0f) {
                        tokenText = encoder.encodeText(textChars, startOffset, endOffset);
                        newText.append(formatter.highlightTerm(tokenText));
                    } else {
                        if (BracketEncoder.test(textChars, startOffset, endOffset)) {
                            newText.append(encoder.encodeText(textChars, startOffset, endOffset));
                        } else {
                            newText.append(textChars, startOffset, endOffset - startOffset);
                        }
                    }

                    lastEndOffset = Math.max(endOffset, lastEndOffset);
                    hangingToken = null;
                } else {
                    lastEndOffset = Math.max(endOffset, lastEndOffset);
                    hangingToken = token;
                }

            } while (((token = tokenStream.next()) != null) && (token.startOffset() < maxDocCharsToAnalyze)); // thruMaxChars


            currentFrag.setScore(fragmentScorer.getFragmentScore());

            lastToken:
            if (hangingToken != null && docFragCtr < docFrags.length) {

                if (startOffset > lastEndOffset) {
                    if (BracketEncoder.test(textChars, lastEndOffset, startOffset)) {
                        newText.append(encoder.encodeText(textChars, lastEndOffset, startOffset));
                    } else {
                        newText.append(textChars, lastEndOffset, startOffset - lastEndOffset);
                    }
                }
                tokenText = encoder.encodeText(textChars, startOffset, endOffset);

                if (score > 0) {
                    newText.append(formatter.highlightTerm(tokenText));
                } else {
                    newText.append(tokenText);
                }
                lastEndOffset = Math.max(endOffset, lastEndOffset);
                currentFrag.setScore(fragmentScorer.getFragmentScore());
                //record stats for a new fragment
                currentFrag.textEndPos = newText.length();
                currentFrag = new BuilderTextFragment(newText, newText.length(), docFrags.length);
                fragmentScorer.startFragment(currentFrag);
                docFrags[docFragCtr] = currentFrag;
            }


            //Test what remains of the original text beyond the point where we stopped analyzing 
            if ( //                  if there is text beyond the last token considered..
                    (lastEndOffset < textChars.length) &&
                    //                  and that text is not too large...
                    (textChars.length < maxDocCharsToAnalyze)) {
                //append it to the last fragment
                newText.append(encoder.encodeText(textChars, lastEndOffset, textChars.length));
            }

            currentFrag.textEndPos = newText.length();
        } finally {
            if (tokenStream != null) {
                try {
                    //tokenStream.close();
                } catch (Exception e) {
                }

            }
        }

        return docFrags;
    }

    //TODO: debug this method!
    public static final BuilderTextFragment[] insureTextFragments(
            CachingTokenFilter tokens,
            final String text,
            LineFragmenter textFragmenter,
            PNSpanScorer fragmentScorer)
            throws IOException {
        LOG.debug("Matching: "+text);
        char[] textChars = text.toCharArray();
        int probableFragSize = 0;
        Matcher match = LINE_TOKEN.matcher(text);
        while (match.find() && match.end() < maxDocCharsToAnalyze) {
            probableFragSize++;
        }

        BuilderTextFragment[] docFrags = new BuilderTextFragment[probableFragSize];
        int docFragCtr = 0;
        StringBuilder newText = new StringBuilder(textChars.length);

        BuilderTextFragment currentFrag = new BuilderTextFragment(newText, newText.length(), docFrags.length);
        fragmentScorer.startFragment(currentFrag);
        CachingTokenFilter cache = new CopyingTokenFilter(tokens);
        Token token = new Token();
        cache.next(token);
        cache.reset();
        try {
            token = cache.next(token);
            Token hangingToken = null;
            char[] tokenText;
            int startOffset = 0;
            int endOffset = 0;
            int lastEndOffset = 0;
            float score = 0;
            textFragmenter.start(text);
            if (token == null) {
                System.err.println("No tokens for fragmenting");
                return new BuilderTextFragment[0];
            }
            thruMaxChars:
            do {
                startOffset = token.startOffset();
                endOffset = token.endOffset();
                if (startOffset > lastEndOffset) { // grab skipped text
                    if (BracketEncoder.test(textChars, lastEndOffset, startOffset)) {
                        newText.append(encoder.encodeText(textChars, lastEndOffset, startOffset));
                    } else {
                        newText.append(textChars, lastEndOffset, startOffset - lastEndOffset);
                    }
                    lastEndOffset = startOffset;
                }

                if (textFragmenter.isNewFragment(token)) {
                    LOG.debug("textFragmenter.isNewFragment");
                    LOG.debug("Fragment score: "+fragmentScorer.getFragmentScore());
                    currentFrag.setScore(fragmentScorer.getFragmentScore());
                    //record stats for a new fragment
                    currentFrag.textEndPos = newText.length();
                    if (currentFrag.getScore() > 0) {
                        docFragCtr++;
                    }
                    if (docFragCtr == docFrags.length) {
                        tokens.reset();
                        CachingTokenFilter scoreTokens = new CopyingTokenFilter(tokens);
                        scoreTokens.next();
                        scoreTokens.reset();
                        mergeContiguousFragments(docFrags, scoreTokens, null);
                        int next = 0;
                        for (int i = 0; i < docFrags.length; i++) {
                            if (docFrags[i] != null) {
                                docFrags[next++] = docFrags[i];
                            } else {
                                LOG.debug("docFrags["+i+"] is null");
                            }
                        }
                        if (next == docFrags.length) {
                            break;
                        } else {
                            docFragCtr = next;
                        }
                    }
                    docFrags[docFragCtr] = new BuilderTextFragment(newText, newText.length(), docFrags.length);
                    currentFrag = (BuilderTextFragment) docFrags[docFragCtr];
                    fragmentScorer.startFragment(currentFrag);
                } else {

                    if (docFragCtr == 0 && docFrags[0] == null) {
                        docFragCtr = 0;
                        docFrags[0] = currentFrag;
                    }
                }

                score = fragmentScorer.getTokenScore(token);
                if (currentFrag.first == null) {
                    currentFrag.first = token;
                    currentFrag.last = currentFrag.first;
                } else {
                    //currentFrag.last.next = token;
                    currentFrag.last = token;
                }

                if (token.startOffset() >= lastEndOffset) {

                    if (score > 0f) {
                        tokenText = encoder.encodeText(textChars, startOffset, endOffset);
                        newText.append(formatter.highlightTerm(tokenText));
                    } else {
                        if (BracketEncoder.test(textChars, startOffset, endOffset)) {
                            newText.append(encoder.encodeText(textChars, startOffset, endOffset));
                        } else {
                            newText.append(textChars, startOffset, endOffset - startOffset);
                        }
                    }

                    lastEndOffset = Math.max(endOffset, lastEndOffset);
                    hangingToken = null;
                } else {
                    lastEndOffset = Math.max(endOffset, lastEndOffset);
                    hangingToken = token;
                }
                currentFrag.setScore(fragmentScorer.getFragmentScore());
                token = cache.next(token);
                if (token != null) {
                    LOG.debug("Token: "+token.toString());
                } else {
                    LOG.debug("Token is null");
                }
            } while ((token != null)); // thruMaxChars

            lastToken:
            if (hangingToken != null && docFragCtr < docFrags.length) {

                if (startOffset > lastEndOffset) {
                    if (BracketEncoder.test(textChars, lastEndOffset, startOffset)) {
                        newText.append(encoder.encodeText(textChars, lastEndOffset, startOffset));
                    } else {
                        newText.append(textChars, lastEndOffset, startOffset - lastEndOffset);
                    }
                }
                tokenText = encoder.encodeText(textChars, startOffset, endOffset);

                if (score > 0) {
                    newText.append(formatter.highlightTerm(tokenText));
                } else {
                    newText.append(tokenText);
                }
                lastEndOffset = Math.max(endOffset, lastEndOffset);
                currentFrag.setScore(fragmentScorer.getFragmentScore());
                //record stats for a new fragment
                currentFrag.textEndPos = newText.length();
                currentFrag = new BuilderTextFragment(newText, newText.length(), docFrags.length);
                fragmentScorer.startFragment(currentFrag);
                docFrags[docFragCtr] = currentFrag;
            }


            //Test what remains of the original text beyond the point where we stopped analyzing 
            if ( //                  if there is text beyond the last token considered..
                    (lastEndOffset < textChars.length) &&
                    //                  and that text is not too large...
                    (textChars.length < maxDocCharsToAnalyze)) {
                //append it to the last fragment
                newText.append(encoder.encodeText(textChars, lastEndOffset, textChars.length));
            }

            currentFrag.textEndPos = newText.length();
        } catch (Exception e) {
            LOG.error(e.toString(), e);
        }
        if (nullArray(docFrags)) LOG.debug("insureTextFragments returning array of nulls");

        return docFrags;
    }

    public static final TextFragment[] getBestTextFragmentsNoGroup(
            CachingTokenFilter cache,
            final String texty,
            LineFragmenter textFragmenter,
            PNSpanScorer fragmentScorer,
            final boolean mergeContiguousFragments,
            int maxNumFragments)
            throws IOException {
//            BuilderTextFragment [] docFrags = getTextFragments(cache, texty, textFragmenter, fragmentScorer);
        BuilderTextFragment[] docFrags = insureTextFragments(cache, texty, textFragmenter, fragmentScorer);
        if (docFrags.length == 0) {
            return docFrags;
        }

        //return the most relevant fragments
        int ctr = 0;
        for (int i = 0; i < docFrags.length; i++) {
            if (docFrags[i] == null) {
                continue;
            }
            if (docFrags[i].getScore() == 0) {
                LOG.debug("Setting docFrags["+i+"] to null.");
                docFrags[i] = null;
            } else {
                ctr++;
            }
        }

        //merge any contiguous fragments to improve readability
        if (mergeContiguousFragments) {
            int merged = mergeContiguousFragments(docFrags, cache, fragmentScorer);

            if (merged != docFrags.length) {
                BuilderTextFragment[] fragTexts = new BuilderTextFragment[merged];
                int mctr = 0;
                for (int i = 0; i < docFrags.length; i++) {
                    if ((docFrags[i] != null) && (docFrags[i].getScore() > 0)) {
                        fragTexts[mctr++] = docFrags[i];
                    }
                }
                docFrags = fragTexts;
            }
        }

        TextFragment[] frag = getTopFromQueue(docFrags, maxNumFragments);

        return frag;
    }

    /*
     * Copied from source of WeightedSpanTermExtractor
     */
    private static IndexReader getReaderForField(String field, CachingTokenFilter cachedTokenFilter) {
        IndexReader reader = (IndexReader) readers.get(field);
        if (reader == null) {
          MemoryIndex indexer = new MemoryIndex();
          indexer.addField(field, cachedTokenFilter);
          IndexSearcher searcher = indexer.createSearcher();
          reader = searcher.getIndexReader();
          readers.put(field, reader);
        }
        return reader;
      }

    //Entry point for SearchPortlet
    public static final TextFragment[] getBestTextFragmentsNoGroup(
            CachingTokenFilter cache,
            final String texty,
            LineFragmenter textFragmenter,
            Query query,
            String field,
            final boolean mergeContiguousFragments,
            int maxNumFragments)
            throws IOException {
        PNSpanScorer scorer = new PNSpanScorer(query, field, cache);
        cache.reset();
        //TODO: check docFrags array for nulls
        BuilderTextFragment[] docFrags = insureTextFragments(cache, texty, textFragmenter, scorer);
        if (nullArray(docFrags)) {
            LOG.debug("insureTextFragments returned array of nulls");
        } else {
            LOG.debug("docFrags not nulls after insureTextFragments");
        }
//            BuilderTextFragment [] docFrags = getTextFragments(cache, texty, textFragmenter, scorer);
        if (docFrags.length == 0) {
            LOG.debug("insureTextFragments returned 0 fragments");
            return docFrags;
        }

        //return the most relevant fragments
        for (int i = 0; i < docFrags.length; i++) {
            if (docFrags[i] != null && docFrags[i].getScore() == 0) {
                LOG.debug("Setting docFrags["+i+"] to null because score == 0");
                docFrags[i] = null;
            }
        }
        if (nullArray(docFrags)) LOG.debug("getBestTextFragmentsNoGroup set all "+docFrags.length+"docFrags to null");


        //merge any contiguous fragments to improve readability
        if (mergeContiguousFragments) {
            int merged = mergeContiguousFragments(docFrags, query, field, cache, scorer);

            if (merged != docFrags.length) {
                BuilderTextFragment[] fragTexts = new BuilderTextFragment[merged];
                int mctr = 0;
                for (int i = 0; i < docFrags.length; i++) {
                    if ((docFrags[i] != null) && (docFrags[i].getScore() > 0)) {
                        fragTexts[mctr++] = docFrags[i];
                    }
                }
                docFrags = fragTexts;
            }
        }
        LOG.debug("docFrags length is "+docFrags.length);

        TextFragment[] frag = getTopFromQueue(docFrags, maxNumFragments);

        LOG.debug("Returning "+frag.length+" text fragments.");

        return frag;
    }

    /** Improves readability of a score-sorted list of TextFragments by merging any fragments
     * that were contiguous in the original text into one larger fragment with the correct order.
     * This will leave a "null" in the array entry for the lesser scored fragment. 
     * 
     * @param frag An array of document fragments in descending score
     */
    private static int mergeContiguousFragments(BuilderTextFragment[] frag, CachingTokenFilter cache, PNSpanScorer scorer) throws IOException {
        boolean mergingStillBeingDone;
        int merged = frag.length;
        if (frag.length > 1) {
            do {
                mergingStillBeingDone = false; //initialise loop control flag
                //for each fragment, scan other frags looking for contiguous blocks
                for (int i = 0; i < frag.length; i++) {
                    if (frag[i] == null) {
                        continue;
                    }
                    //merge any contiguous blocks 
                    for (int x = 0; x < frag.length; x++) {
                        if (frag[x] == null) {
                            continue;
                        }
                        if (x == i) {
                            continue;
                        }
                        if (frag[i] == null) {
                            break;
                        }
                        BuilderTextFragment frag1 = null;
                        BuilderTextFragment frag2 = null;
                        int frag1Num = 0;
                        int frag2Num = 0;
                        int bestScoringFragNum;
                        int worstScoringFragNum;
                        //if blocks are contiguous....
                        if (frag[i].follows(frag[x])) {
                            frag1 = frag[x];
                            frag1Num = x;
                            frag2 = frag[i];
                            frag2Num = i;
                        } else {
                            if (frag[x].follows(frag[i])) {
                                frag1 = frag[i];
                                frag1Num = i;
                                frag2 = frag[x];
                                frag2Num = x;
                            } else {
                                continue; // neither follow the other
                            }
                        }
//                        System.out.println("Merging required...");
                        float test = (scorer == null) ? (Math.max(frag1.score, frag2.score) + 0.1f) : testMerge(frag1, frag2, cache, scorer);
                        if (test > frag1.score && test > frag2.score) {
                            if (frag1.score > frag2.score) {
                                bestScoringFragNum = frag1Num;
                                worstScoringFragNum = frag2Num;
                            } else {
                                bestScoringFragNum = frag2Num;
                                worstScoringFragNum = frag1Num;
                            }
                            frag1.merge(frag2, test);

                            frag[worstScoringFragNum] = null;
                            merged--;
                            mergingStillBeingDone = true;
                            frag[bestScoringFragNum] = frag1;
                        }
                    }
                }
            } while (mergingStillBeingDone);
        }
        return merged;
    }

    private static int mergeContiguousFragments(BuilderTextFragment[] frag, Query query, String field, CachingTokenFilter cache, PNSpanScorer scorer) throws IOException {
        if (nullArray(frag)) LOG.debug("mergeContiguousFragments got null array");
        boolean mergingStillBeingDone;
        int merged = frag.length;
        if (frag.length > 1) {
            do {
                mergingStillBeingDone = false; //initialise loop control flag
                //for each fragment, scan other frags looking for contiguous blocks
                for (int i = 0; i < frag.length; i++) {
                    if (frag[i] == null) {
                        continue;
                    }
                    //merge any contiguous blocks 
                    for (int x = 0; x < frag.length; x++) {
                        if (frag[x] == null) {
                            continue;
                        }
                        if (x == i) {
                            continue;
                        }
                        if (frag[i] == null) {
                            break;
                        }
                        BuilderTextFragment frag1 = null;
                        BuilderTextFragment frag2 = null;
                        int frag1Num = 0;
                        int frag2Num = 0;
                        int bestScoringFragNum;
                        int worstScoringFragNum;
                        //if blocks are contiguous....
                        if (frag[i].follows(frag[x])) {
                            frag1 = frag[x];
                            frag1Num = x;
                            frag2 = frag[i];
                            frag2Num = i;
                        } else {
                            if (frag[x].follows(frag[i])) {
                                frag1 = frag[i];
                                frag1Num = i;
                                frag2 = frag[x];
                                frag2Num = x;
                            } else {
                                continue; // neither follow the other
                            }
                        }
//                        System.out.println("Merging required...");
                        float test = testMerge(frag1, frag2, cache, scorer);
                        if (test > frag1.score && test > frag2.score) {
                            if (frag1.score > frag2.score) {
                                bestScoringFragNum = frag1Num;
                                worstScoringFragNum = frag2Num;
                            } else {
                                bestScoringFragNum = frag2Num;
                                worstScoringFragNum = frag1Num;
                            }
                            frag1.merge(frag2, test);

                            frag[worstScoringFragNum] = null;
                            merged--;
                            mergingStillBeingDone = true;
                            frag[bestScoringFragNum] = frag1;
                        }
                    }
                }
            } while (mergingStillBeingDone);
        }
        if (nullArray(frag)) LOG.debug("mergeContiguousFragments returning null array.");
        return merged;
    }

    private static boolean nullArray(Object[] stuff) {
        for (Object o:stuff) {
            if (o != null) {
                return false;
            }
        }
        return true;
    }

    static boolean tokenEquals(Token t1, Token t2) {
        if (t1 == null || t2 == null) {
            return false;
        }
        if (t1.startOffset() != t2.startOffset()) {
            return false;
        }
        if (t1.endOffset() != t2.endOffset()) {
            return false;
        }
        if (t1.termLength() != t2.termLength()) {
            return false;
        }
        char[] buff1 = t1.termBuffer();
        char[] buff2 = t2.termBuffer();
        for (int i = 0; i < t1.termLength(); i++) {
            if (buff1[i] != buff2[i]) {
                return false;
            }
        }
        return (t1.getPositionIncrement() == t2.getPositionIncrement());
    }

    private static float testMerge(BuilderTextFragment frag1, BuilderTextFragment frag2, CachingTokenFilter cache, PNSpanScorer scorer) throws IOException {
        float max = Math.max(frag1.score, frag2.score);
        //BuilderTextFragment test = new BuilderTextFragment(frag1.builder,frag1.textStartPos,frag2.textEndPos);
        cache.reset();
        scorer.reset();
        Token flag;
        while ((flag = cache.next()) != null && !tokenEquals(flag, frag1.first)) {
            scorer.getTokenScore(flag);
        }
        if (flag == null) {
            return -1; // this is an error state
        }
        scorer.startFragment(null);
        scorer.getTokenScore(flag);
        /**
         * The last1,second2,and s2next variables are flags to hack around the inability of the spanscorer to know that an intermediate scoring token
         * in a matching span is out of order. It's relevant to ordered SpanNear queries in which a matching pattern appears more than once in the span,
         * but only once in the correct order.  This isn't a fix, but it band-aids the cases in which the correct match was one of the first terms on the next line. 
         */
        float last1 = 0;
        float second2 = 0;
        boolean s2next = false;
        do {
            flag = cache.next();
            float score = scorer.getTokenScore(flag);
            if (tokenEquals(frag1.last, flag)) {
                last1 = score;
            } else if (tokenEquals(frag2.first, flag)) {
                s2next = true;
            } else if (s2next) {
                second2 += score;
                if (flag.getPositionIncrement() > 0) {
                    s2next = false;
                }
            }
        } while (flag != null && !tokenEquals(flag, frag2.last));
        float score = scorer.getFragmentScore();
        if (last1 != 0 && second2 != 0 && score == max) {
            score += .1f;
        }
        LOG.debug("testMerge scores: "+score+", "+max);
        return Math.max(score, max);
    }

    static BuilderTextFragment[] getTopFromQueue(BuilderTextFragment[] docFrags, int maxNumFragments) {
        PeekFragmentQueue fragQueue = new PeekFragmentQueue(maxNumFragments);
        for (BuilderTextFragment i : docFrags) {
            if (i != null && i.getScore() > 0) {
                fragQueue.insert(i);
            }
            if (i == null) {
                LOG.debug("Null text fragment");
            } else {
                LOG.debug("Fragment "+i.getFragNum()+" score: "+i.getScore());
            }

        }

        //return the most relevant fragments
        BuilderTextFragment[] frag = new BuilderTextFragment[fragQueue.size()];
        for (int j = frag.length - 1; j >= 0; j--) {
            frag[j] = (BuilderTextFragment) fragQueue.pop();
        }
        fragQueue.clear();
        return frag;
    }
    static final TextFragmentComparator fragComp = new TextFragmentComparator();

    static TextFragment[] getTopFromSort(TextFragment[] docFrags, int end, int maxNumFragments) {
        int numHits = 0;
        for (TextFragment frag : docFrags) {
            if (frag != null && frag.getScore() > 0) {
                numHits++;
            }
        }
        int size = Math.min(maxNumFragments, numHits);
        java.util.Arrays.sort(docFrags, 0, end, fragComp);

        //return the most relevant fragments
        TextFragment[] frag = new TextFragment[size];
        System.arraycopy(docFrags, 0, frag, 0, size);
        return frag;
    }

    static TextFragment[] getTopFromOrder(TextFragment[] docFrags, int end, int maxNumFragments) {

        int size = Math.min(maxNumFragments, docFrags.length);

        //return the most relevant fragments
        TextFragment[] frag = new TextFragment[size];
        System.arraycopy(docFrags, 0, frag, 0, size);
        return frag;
    }

    static class TextFragmentComparator implements java.util.Comparator<TextFragment> {

        public int compare(TextFragment a, TextFragment b) {
            if (b == null) {
                return Integer.MIN_VALUE; // float nulls t end
            }
            if (a == null) {
                return Integer.MAX_VALUE; // float nulls to end
            }
            float result = b.getScore() - a.getScore();
            if (result == 0) {
                result = a.fragNum - b.fragNum; // lines in order
            }
            return (int) (result); // because high scores are stronger
        }
    }

    static class PeekFragmentQueue extends PriorityQueue {

        public PeekFragmentQueue(int size) {
            initialize(size);
        }

        public final boolean lessThan(Object a, Object b) {
            if (a == null && b == null) {
                return false;
            }
            if (a == null) {
                return true;
            }
            if (b == null) {
                return false;
            }
            TextFragment fragA = (TextFragment) a;
            TextFragment fragB = (TextFragment) b;
            if (fragA.getScore() == fragB.getScore()) {
                return fragA.fragNum < fragB.fragNum; // lines in order
            } else {
                return fragA.getScore() < fragB.getScore();
            }
        }

        Object peek(int i) {

            if (i > size()) {
                return null;
            }
            return heap[i];
        }

        Object peek() {
            return (peek(1));
        }
    }
}