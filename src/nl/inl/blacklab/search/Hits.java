/*******************************************************************************
 * Copyright (c) 2010, 2012 Institute for Dutch Lexicology
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *******************************************************************************/
package nl.inl.blacklab.search;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import nl.inl.blacklab.forwardindex.Terms;
import nl.inl.blacklab.index.complex.ComplexFieldUtil;
import nl.inl.blacklab.search.grouping.HitProperty;
import nl.inl.blacklab.search.grouping.HitPropertyMultiple;
import nl.inl.blacklab.search.lucene.BLSpans;
import nl.inl.blacklab.search.lucene.BLSpansWrapper;
import nl.inl.util.StringUtil;

import org.apache.log4j.Logger;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.BooleanQuery.TooManyClauses;
import org.apache.lucene.search.spans.SpanQuery;
import org.apache.lucene.search.spans.Spans;

/**
 * Represents a list of Hit objects. Also maintains information about the context (concordance)
 * information stored in the Hit objects.
 */
public class Hits implements Iterable<Hit> {
	protected static final Logger logger = Logger.getLogger(Hits.class);

	/**
	 * The hits.
	 */
	protected List<Hit> hits;

	/**
	 * The concordances, if they have been retrieved.
	 *
	 * NOTE: this will always be null if not all the hits have been retrieved.
	 */
	protected Map<Hit, Concordance> concordances;

	/**
	 * The searcher object.
	 */
	protected Searcher searcher;

	/**
	 * If we have context information, this specifies the property (i.e. word, lemma, pos) the context came from.
	 * Otherwise, it is null.
	 */
	protected List<String> contextFieldsPropName;

	/**
	 * The default field to use for retrieving concordance information.
	 */
	protected String concordanceFieldName;

	/**
	 * Lucene name for the main property field of the current contents field.
	 */
	private String concordanceMainFieldPropName;

	/**
	 * Did we completely read our Spans object?
	 */
	protected boolean sourceSpansFullyRead = true;

	/**
	 * Our Spans object, which may not have been fully read yet.
	 */
	protected BLSpans sourceSpans;

	/**
	 * Stop retrieving hits after this number.
	 * (-1 = don't stop retrieving)
	 */
	private static int defaultMaxHitsToRetrieve = 1000000;

	/**
	 * Stop counting hits after this number.
	 * (-1 = don't stop counting)
	 */
	private static int defaultMaxHitsToCount = -1;

	/**
	 * Stop retrieving hits after this number.
	 * (-1 = don't stop retrieving)
	 */
	private int maxHitsToRetrieve = defaultMaxHitsToRetrieve;

	/**
	 * Stop counting hits after this number.
	 * (-1 = don't stop counting)
	 */
	private int maxHitsToCount = defaultMaxHitsToCount;

	/**
	 * If true, we've stopped retrieving hits because there are more than
	 * the maximum we've set.
	 */
	private boolean maxHitsRetrieved = false;

	/**
	 * If true, we've stopped counting hits because there are more than
	 * the maximum we've set.
	 */
	private boolean maxHitsCounted = false;

	/**
	 * The number of hits we've seen and counted so far. May be more than
	 * the number of hits we've retrieved if that exceeds maxHitsToRetrieve.
	 */
	private int hitsCounted = 0;

	/**
	 * The number of separate documents we've seen and counted so far.
	 */
	private int docsCounted = 0;

	/**
	 * Document the previous hit was in, so we can count separate documents.
	 */
	private int previousHitDoc = -1;

	/**
	 * The desired context size (number of words to fetch around hits).
	 * Defaults to Searcher.getDefaultContextSize().
	 */
	private int desiredContextSize;

	/**
	 * The current context size (number of words around hits we now have).
	 */
	private int currentContextSize;

	/**
	 * Construct an empty Hits object
	 *
	 * @param searcher
	 *            the searcher object
	 */
	public Hits(Searcher searcher) {
		this(searcher, searcher.getContentsFieldMainPropName());
	}

	/**
	 * Construct an empty Hits object
	 *
	 * @param searcher
	 *            the searcher object
	 * @param concordanceFieldPropName
	 *            field to use by default when finding concordances
	 */
	public Hits(Searcher searcher, String concordanceFieldPropName) {
		this.searcher = searcher;
		hits = new ArrayList<Hit>();
		hitsCounted = 0;
		setConcordanceField(concordanceFieldPropName);
		desiredContextSize = searcher == null ? 0 /* only for test */: searcher
				.getDefaultContextSize();
		currentContextSize = -1;
	}

	/**
	 * Construct an empty Hits object.
	 *
	 * If possible, don't use this constructor, use the one that takes
	 * a SpanQuery, as it's more efficient.
	 *
	 * @param searcher
	 *            the searcher object
	 * @param concordanceFieldPropName
	 *            field to use by default when finding concordances
	 * @param source
	 *            where to retrieve the Hit objects from
	 * @deprecated supply a SpanQuery to a Hits object instead
	 */
	@Deprecated
	public Hits(Searcher searcher, String concordanceFieldPropName, Spans source) {
		this(searcher, concordanceFieldPropName);

		sourceSpans = BLSpansWrapper.optWrap(source);
		sourceSpansFullyRead = false;
	}

	/**
	 * Construct an empty Hits object
	 *
	 * @param searcher
	 *            the searcher object
	 * @param concordanceFieldPropName
	 *            field to use by default when finding concordances
	 * @param sourceQuery
	 *            the query to execute to get the hits
	 * @throws TooManyClauses if the query is overly broad (expands to too many terms)
	 */
	public Hits(Searcher searcher, String concordanceFieldPropName, SpanQuery sourceQuery)
			throws TooManyClauses {
		this(searcher, concordanceFieldPropName);

		sourceSpans = findSpans(sourceQuery);
		// logger.debug("SPANS: " + sourceSpans);
		sourceSpansFullyRead = false;
	}

	/**
	 * Construct an empty Hits object
	 *
	 * @param searcher
	 *            the searcher object
	 * @param sourceQuery
	 *            the query to execute to get the hits
	 * @throws TooManyClauses if the query is overly broad (expands to too many terms)
	 */
	public Hits(Searcher searcher, SpanQuery sourceQuery) throws TooManyClauses {
		this(searcher, searcher.getContentsFieldMainPropName(), sourceQuery);
	}

	/** Returns the context size.
	 * @return context size (number of words to fetch around hits)
	 */
	public int getContextSize() {
		return desiredContextSize;
	}

	/** Sets the desired context size.
	 * @param contextSize the context size (number of words to fetch around hits)
	 */
	public void setContextSize(int contextSize) {
		if (this.desiredContextSize == contextSize)
			return; // no need to reset anything
		this.desiredContextSize = contextSize;

		// Reset context and concordances so we get the correct context size next time
		currentContextSize = -1;
		contextFieldsPropName = null;
		concordances = null;
	}

	/**
	 * Executes the SpanQuery to get a Spans object.
	 *
	 * @param spanQuery
	 *            the query
	 * @return the results object
	 * @throws BooleanQuery.TooManyClauses
	 *             if a wildcard or regular expression term is overly broad
	 */
	BLSpans findSpans(SpanQuery spanQuery) throws BooleanQuery.TooManyClauses {
		try {
			IndexReader reader = null;
			if (searcher != null) { // this may happen while testing with stub classes
				reader = searcher.getIndexReader();
			}
			spanQuery = (SpanQuery) spanQuery.rewrite(reader);
			return BLSpansWrapper.optWrap(spanQuery.getSpans(reader));
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	/**
	 * Were all hits retrieved, or did we stop because there were too many?
	 * @return true if all hits were retrieved
	 * @deprecated renamed to maxHitsRetrieved()
	 */
	@Deprecated
	public boolean tooManyHits() {
		return maxHitsRetrieved();
	}

	/**
	 * Did we stop retrieving hits because we reached the maximum?
	 * @return true if we reached the maximum and stopped retrieving hits
	 */
	public boolean maxHitsRetrieved() {
		return maxHitsRetrieved;
	}

	/**
	 * Did we stop counting hits because we reached the maximum?
	 * @return true if we reached the maximum and stopped counting hits
	 */
	public boolean maxHitsCounted() {
		return maxHitsCounted;
	}

	/**
	 * Get the list of hits.
	 *
	 * @return the list of hits
	 * @deprecated Breaks optimizations. Use iteration or subList() instead.
	 */
	@Deprecated
	public List<Hit> getHits() {
		try {
			ensureAllHitsRead();
		} catch (InterruptedException e) {
			// Interrupted; just return the hits we've gathered so far.
		}
		return Collections.unmodifiableList(hits);
	}

	/**
	 * If we still have only partially read our Spans object,
	 * read the rest of it and add all the hits.
	 * @throws InterruptedException if the thread was interrupted during this operation
	 */
	private void ensureAllHitsRead() throws InterruptedException {
		ensureHitsRead(-1);
	}

	/**
	 * Ensure that we have read at least as many hits as specified in the parameter.
	 *
	 * @param number the minimum number of hits that will have been read when this method
	 *   returns (unless there are fewer hits than this); if negative, reads all hits
	 * @throws InterruptedException if the thread was interrupted during this operation
	 */
	void ensureHitsRead(int number) throws InterruptedException {
		if (sourceSpansFullyRead)
			return;

		boolean readAllHits = number < 0;
		Thread currentThread = Thread.currentThread();
		try {
			while (readAllHits || hits.size() < number) {

				// Check if the thread should terminate
				if (currentThread.isInterrupted())
					throw new InterruptedException("Thread was interrupted while gathering hits");

				// Stop if we're at the maximum number of hits we want to count
				if (maxHitsToCount >= 0 && hitsCounted >= maxHitsToCount) {
					maxHitsCounted = true;
					break;
				}

				// Advance to next hit
				if (!sourceSpans.next()) {
					sourceSpansFullyRead = true;
					break;
				}

				// Count the hit and add it (unless we've reached the maximum number of hits we want)
				hitsCounted++;
				int hitDoc = sourceSpans.doc();
				if (hitDoc != previousHitDoc) {
					docsCounted++;
					previousHitDoc = hitDoc;
				}
				maxHitsRetrieved = maxHitsToRetrieve >= 0 && hits.size() >= maxHitsToRetrieve;
				if (!maxHitsRetrieved)
					hits.add(sourceSpans.getHit());
			}
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	/**
	 * Sort the list of hits.
	 *
	 * Note that if the thread is interrupted during this, sort may return
	 * without the hits actually being fully read and sorted. We don't want
	 * to add throws declarations to our whole API, so we assume the calling
	 * method will check for thread interruption if the application uses it.
	 *
	 * @param sortProp
	 *            the hit property/properties to sort on
	 * @param reverseSort
	 *            if true, sort in descending order
	 */
	public void sort(HitProperty[] sortProp, boolean reverseSort) {
		if (sortProp.length == 1)
			sort(sortProp[0], reverseSort);
		else
			sort(new HitPropertyMultiple(sortProp), reverseSort);
	}

	/**
	 * Sort the list of hits.
	 *
	 * Note that if the thread is interrupted during this, sort may return
	 * without the hits actually being fully read and sorted. We don't want
	 * to add throws declarations to our whole API, so we assume the calling
	 * method will check for thread interruption if the application uses it.
	 *
	 * @param sortProp
	 *            the hit property/properties to sort on
	 */
	public void sort(HitProperty... sortProp) {
		sort(sortProp, false);
	}

	/**
	 * Sort the list of hits.
	 *
	 * Note that if the thread is interrupted during this, sort may return
	 * without the hits actually being fully read and sorted. We don't want
	 * to add throws declarations to our whole API, so we assume the calling
	 * method will check for thread interruption if the application uses it.
	 *
	 * @param sortProp
	 *            the hit property to sort on
	 * @param reverseSort
	 *            if true, sort in descending order
	 */
	public void sort(final HitProperty sortProp, boolean reverseSort) {
		try {
			ensureAllHitsRead();
		} catch (InterruptedException e) {
			// Thread was interrupted; don't complete the operation but return
			// and let the caller detect and deal with the interruption.
			return;
		}

		// Do we need context and don't we have it yet?
		List<String> requiredContext = sortProp.needsContext();
		if (requiredContext != null
				&& (!requiredContext.equals(contextFieldsPropName) || currentContextSize != desiredContextSize)) {
			// Get 'em
			findContext(requiredContext);
		}

		Collections.sort(hits, sortProp);
		if (reverseSort) {
			// Instead of creating a new Comparator that reverses the order of the
			// sort property (which adds an extra layer of indirection to each of the
			// O(n log n) comparisons), just reverse the hits now (which runs
			// in linear time).
			Collections.reverse(hits);
		}
	}

	/**
	 * Add a hit to the list
	 *
	 * @param hit
	 *            the hit
	 */
	public void add(Hit hit) {
		try {
			ensureAllHitsRead();
		} catch (InterruptedException e) {
			// Thread was interrupted; don't complete the operation but return
			// and let the caller detect and deal with the interruption.
			return;
		}
		hits.add(hit);
		hitsCounted++;
		int hitDoc = hit.doc;
		if (hitDoc != previousHitDoc) {
			docsCounted++;
			previousHitDoc = hitDoc;
		}
	}

	/**
	 * Determines if there are at least a certain number of hits
	 *
	 * This may be used if we don't want to process all hits (which
	 * may be a lot) but we do need to know something about the size
	 * of the result set (such as for paging).
	 *
	 * Note that this method applies to the hits retrieved, which may
	 * be less than the total number of hits (depending on maxHitsToRetrieve).
	 *
	 * @param lowerBound the number we're testing against
	 *
	 * @return true if the size of this set is at least lowerBound, false otherwise.
	 */
	public boolean sizeAtLeast(int lowerBound) {
		try {
			// Try to fetch at least this many hits
			ensureHitsRead(lowerBound);
		} catch (InterruptedException e) {
			// Thread was interrupted; abort operation
			// and let client decide what to do
		}

		return hits.size() >= lowerBound;
	}

	/**
	 * Return the number of hits available.
	 *
	 * Note that this method applies to the hits retrieved, which may
	 * be less than the total number of hits (depending on maxHitsToRetrieve).
	 * Use totalSize() to find the total hit count (which may also be limited
	 * depending on maxHitsToCount).
	 *
	 * @return the number of hits available
	 */
	public int size() {
		try {
			// Probably not all hits have been seen yet. Collect them all.
			ensureAllHitsRead();
		} catch (InterruptedException e) {
			// Thread was interrupted; don't complete the operation but return
			// and let the caller detect and deal with the interruption.
			// Returned value is probably not the correct total number of hits,
			// but will not cause any crashes. The thread was interrupted anyway,
			// the value should never be presented to the user.
		}
		return hits.size();
	}

	/**
	 * Return the total number of hits.
	 *
	 * NOTE: Depending on maxHitsToRetrieve, hit retrieval may stop
	 * before all hits are seen. We do keep counting hits though
	 * (until we reach maxHitsToCount, or that value is negative).
	 * This method returns our total hit count. Some of these hits
	 * may not be available.
	 *
	 * @return the total hit count
	 */
	public int totalSize() {
		return hitsCounted;
	}

	/**
	 * Return the total number of documents in all hits.
	 * This counts documents even in hits that are not stored, only counted.
	 *
	 * @return the total number of documents.
	 */
	public int numberOfDocs() {
		try {
			ensureAllHitsRead();
		} catch (InterruptedException e) {
			// Thread was interrupted; don't complete the operation but return
			// and let the caller detect and deal with the interruption.
			// Returned value is probably not the correct total number of hits,
			// but will not cause any crashes. The thread was interrupted anyway,
			// the value should never be presented to the user.
		}
		return docsCounted;
	}

	/**
	 * Return an iterator over these hits.
	 *
	 * @return the iterator
	 */
	@Override
	public Iterator<Hit> iterator() {
		// Construct a custom iterator that iterates over the hits in the hits
		// list, but can also take into account the Spans object that may not have
		// been fully read. This ensures we don't instantiate Hit objects for all hits
		// if we just want to display the first few.
		return new Iterator<Hit>() {

			int index = -1;

			@Override
			public boolean hasNext() {
				// Do we still have hits in the hits list?
				try {
					ensureHitsRead(index + 2);
				} catch (InterruptedException e) {
					// Thread was interrupted. Don't finish reading hits and accept possibly wrong
					// answer.
					// Client must detect the interruption and stop the thread.
				}
				return hits.size() >= index + 2;
			}

			@Override
			public Hit next() {
				// Check if there is a next, taking unread hits from Spans into account
				if (hasNext()) {
					index++;
					return hits.get(index);
				}
				throw new NoSuchElementException();
			}

			@Override
			public void remove() {
				throw new UnsupportedOperationException();
			}

		};
	}

	/**
	 * Return the specified hit.
	 *
	 * @param i
	 *            index of the desired hit
	 * @return the hit, or null if it's beyond the last hit
	 */
	public Hit get(int i) {
		try {
			ensureHitsRead(i + 1);
		} catch (InterruptedException e) {
			// Thread was interrupted. Required hit hasn't been gathered;
			// we will just return null.
		}
		if (i >= hits.size())
			return null;
		return hits.get(i);
	}

	/**
	 * Return the concordance for the specified hit.
	 *
	 * The first call to this method will fetch the concordances for all the hits in this
	 * Hits object. So make sure to select an appropriate HitsWindow first: don't call this
	 * method on a Hits set with >1M hits unless you really want to display all of them in one
	 * go.
	 *
	 * @param h the hit
	 * @return concordance for this hit
	 */
	public Concordance getConcordance(Hit h) {
		return getConcordance(h, desiredContextSize);
	}

	/**
	 * Get a concordance with a custom context size.
	 *
	 * Don't call this directly for displaying a list of results. In that case,
	 * just instantiate a HitsWindow, call setContextSize() on it to set a
	 * default context size and call getConcordance(Hit) for each hit. That's
	 * more efficient if you're dealing with many hits.
	 *
	 * This method is mostly just for getting a larger snippet around
	 * a single hit.
	 *
	 * @param h the hit
	 * @param contextSize the context size for this concordance
	 *   (only use if you want a different one than the preset preference)
	 * @return concordance for this hit
	 */
	public Concordance getConcordance(Hit h, int contextSize) {
		if (contextSize != desiredContextSize) {
			// Different context size than the default for the whole set;
			// We probably want to show a hit with a larger snippet around it
			// (say, 50 words or so). Don't clobber the context of the other
			// hits, just fetch this snippet separately.
			return searcher.getConcordance(concordanceFieldName, h, contextSize);
		}

		// Default context size. Read all hits and find concordances for all of them
		// in batch.
		try {
			ensureAllHitsRead();
		} catch (InterruptedException e) {
			// Thread was interrupted. Just go ahead with the hits we did
			// get, so at least we can return a valid concordance object and
			// not break the calling method. It is responsible for checking
			// for thread interruption (only some applications use this at all,
			// so throwing exceptions from all methods is too inconvenient)
		}
		if (concordances == null) {
			findConcordances(); // just try to find the default concordances
		}
		Concordance conc = concordances.get(h);
		if (conc == null)
			throw new RuntimeException("Concordance for hit not found: " + h);
		return conc;
	}

	/**
	 * Retrieve concordances for the hits.
	 *
	 * You shouldn't have to call this manually, as it's automatically called when
	 * you call getConcordance() for the first time.
	 */
	void findConcordances() {
		try {
			ensureAllHitsRead();
		} catch (InterruptedException e) {
			// Thread was interrupted. Just go ahead with the hits we did
			// get, so at least we'll have valid concordances.
		}
		// Make sure we don't have the desired concordances already
		if (concordances != null) {
			return;
		}

		// Get the concordances
		concordances = searcher.retrieveConcordances(concordanceFieldName, hits, desiredContextSize);
	}

	/**
	 * Retrieve context words for the hits.
	 *
	 * @param fieldPropName
	 *            the field and property to use for the context
	 */
	public void findContext(String fieldPropName) {
		findContext(Arrays.asList(fieldPropName));
	}

	/**
	 * Retrieve context words for the hits.
	 *
	 * @param fieldProps
	 *            the field and properties to use for the context
	 */
	public void findContext(List<String> fieldProps) {
		try {
			ensureAllHitsRead();
		} catch (InterruptedException e) {
			// Thread was interrupted. Just go ahead with the hits we did
			// get, so at least we can return with valid context.
		}
		// Make sure we don't have the desired context already
		if (contextFieldsPropName != null && fieldProps.equals(contextFieldsPropName)
				&& desiredContextSize == currentContextSize) {
			return;
		}

		// Get the context
		searcher.retrieveContext(fieldProps, hits, desiredContextSize);
		currentContextSize = desiredContextSize;

		contextFieldsPropName = fieldProps == null ? fieldProps : new ArrayList<String>(fieldProps);
	}

	/**
	 * Retrieve context for the hits, for sorting/grouping.
	 *
	 * NOTE: you should never have to call this manually; it is
	 * called if needed by the sorting/grouping code.
	 *
	 * Uses the main property field.
	 */
	void findContext() {
		findContext(concordanceMainFieldPropName);
	}

	/**
	 * Clear any cached concordances so new ones will be created on next call to getConcordance().
	 */
	public void clearConcordances() {
		concordances = null;
	}

	/**
	 * Clear any cached concordances so new ones will be created when necessary.
	 */
	public void clearContext() {
		for (Hit hit: hits) {
			hit.context = null;
		}
		contextFieldsPropName = null;
	}

	/**
	 * Count occurrences of context words around hit.
	 *
	 * Uses the default contents field for collocations.
	 *
	 * @return the frequency of each occurring token
	 */
	public TokenFrequencyList getCollocations() {
		return getCollocations(null, null);
	}

	/**
	 * Count occurrences of context words around hit.
	 *
	 * @param propName the property to use for the collocations, or null if default
	 * @param ctx query execution context, containing the sensitivity settings
	 *
	 * @return the frequency of each occurring token
	 */
	public TokenFrequencyList getCollocations(String propName, QueryExecutionContext ctx) {
		findContext(ctx.luceneField(false));
		Map<Integer, Integer> coll = new HashMap<Integer, Integer>();
		for (Hit hit: hits) {
			int[] context = hit.context;

			// Count words
			for (int i = 0; i < context.length; i++) {
				if (i >= hit.contextHitStart && i < hit.contextRightStart)
					continue; // don't count words in hit itself, just around
				int w = context[i];
				Integer n = coll.get(w);
				if (n == null)
					n = 1;
				else
					n++;
				coll.put(w, n);
			}
		}

		// Get the actual words from the sort positions
		boolean caseSensitive = searcher.isDefaultSearchCaseSensitive();
		boolean diacSensitive = searcher.isDefaultSearchDiacriticsSensitive();
		TokenFrequencyList collocations = new TokenFrequencyList(coll.size());
		// TODO: get collocations for multiple contexts?
		Terms terms = searcher.getTerms(contextFieldsPropName.get(0));
		for (Map.Entry<Integer, Integer> e: coll.entrySet()) {
			String word = terms.getFromSortPosition(e.getKey());
			if (!diacSensitive) {
				word = StringUtil.removeAccents(word);
			}
			if (!caseSensitive) {
				word = word.toLowerCase();
			}
			collocations.add(new TokenFrequency(word, e.getValue()));
		}
		return collocations;
	}

	/**
	 * Returns the searcher object.
	 *
	 * @return the searcher object.
	 */
	public Searcher getSearcher() {
		return searcher;
	}

	/**
	 * Returns the field to use for retrieving concordances.
	 *
	 * @return the field name
	 */
	public String getConcordanceFieldName() {
		return concordanceFieldName;
	}

	/**
	 * Sets the field to use for retrieving concordances.
	 *
	 * @param concordanceFieldName
	 *            the field name
	 */
	public void setConcordanceField(String concordanceFieldName) {
		this.concordanceFieldName = concordanceFieldName;
		if (searcher == null) {
			// Can occur during testing. Just use default main property name.
			concordanceMainFieldPropName = ComplexFieldUtil.propertyField(concordanceFieldName,
					ComplexFieldUtil.getDefaultMainPropName());
		} else {
			// Get the main property name from the index structure.
			concordanceMainFieldPropName = ComplexFieldUtil.mainPropertyField(
					searcher.getIndexStructure(), concordanceFieldName);
		}
	}

	/**
	 * Get the field our current concordances were retrieved from
	 *
	 * @return the field name
	 */
	public List<String> getContextFieldPropName() {
		return contextFieldsPropName;
	}

	/**
	 * Retrieve a sublist of hits.
	 *
	 * If toIndex is beyond the last hit, will return a list up to and
	 * including the last hit.
	 *
	 * @param fromIndex first hit to include in the resulting list
	 * @param toIndex first hit not to include in the resulting list
	 * @return the sublist
	 */
	public List<Hit> subList(int fromIndex, int toIndex) {
		try {
			ensureHitsRead(toIndex);
		} catch (InterruptedException e) {
			// Thread was interrupted. We may not even have read
			// the first hit in the sublist, so just return an empty list.
			return Collections.emptyList();
		}
		if (toIndex > hits.size())
			toIndex = hits.size();
		return hits.subList(fromIndex, toIndex);
	}

	/**
	 * Set the field properties to retrieve context from
	 * @param contextField the field properties
	 */
	public void setContextField(List<String> contextField) {
		this.contextFieldsPropName = contextField == null ? null : new ArrayList<String>(contextField);
	}

	/** @return the default maximum number of hits to retrieve. */
	public static int getDefaultMaxHitsToRetrieve() {
		return defaultMaxHitsToRetrieve;
	}

	/** Set the default maximum number of hits to retrieve
	 * @param n the number of hits, or -1 for no limit
	 */
	public static void setDefaultMaxHitsToRetrieve(int n) {
		Hits.defaultMaxHitsToRetrieve = n;
	}

	/** @return the default maximum number of hits to count. */
	public static int getDefaultMaxHitsToCount() {
		return defaultMaxHitsToCount;
	}

	/** Set the default maximum number of hits to count
	 * @param n the number of hits, or -1 for no limit
	 */
	public static void setDefaultMaxHitsToCount(int n) {
		Hits.defaultMaxHitsToCount = n;
	}

	/** @return the maximum number of hits to retrieve. */
	public int getMaxHitsToRetrieve() {
		return maxHitsToRetrieve;
	}

	/** Set the maximum number of hits to retrieve
	 * @param n the number of hits, or -1 for no limit
	 */
	public void setMaxHitsToRetrieve(int n) {
		this.maxHitsToRetrieve = n;
	}

	/** @return the maximum number of hits to count. */
	public int getMaxHitsToCount() {
		return maxHitsToCount;
	}

	/** Set the maximum number of hits to count
	 * @param n the number of hits, or -1 for no limit
	 */
	public void setMaxHitsToCount(int n) {
		this.maxHitsToCount = n;
	}

}
