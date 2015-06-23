package com.s24.search.solr.util.packed;

import static com.google.common.base.Preconditions.checkArgument;

import org.apache.lucene.util.OpenBitSet;
import org.apache.lucene.util.packed.PackedInts;

public class SparsePackedMutable extends PackedInts.Mutable {

   private final int DEFAULT_BUFFER_SIZE = PackedInts.DEFAULT_BUFFER_SIZE * 1024; // 1M

   private final long defaultValue;
   private final int intialSize;
   private final float acceptableOverheadRatio;
   private OffsetGrowableWriter internalWriter;
   private OpenBitSet docs;
   private int maxDocId = -1;

   public SparsePackedMutable(long defaultValue, int maxValueCount, int intialSize, float acceptableOverheadRatio) {
      this.defaultValue = defaultValue;
      this.acceptableOverheadRatio = acceptableOverheadRatio;
      this.intialSize = intialSize;

      // create internal helpers
      this.internalWriter = new OffsetGrowableWriter(4, intialSize, acceptableOverheadRatio);
      this.docs = new OpenBitSet(maxValueCount);
   }

   @Override
   public void set(int doc, long value) {
      try {
         // replace value
         if (docs.get(doc)) {

            // get index of doc and replace value
            internalWriter.set(internalIndexOf(doc), value);
         } else if (docs.cardinality() == 0) {
            internalWriter.set(0, value);
            docs.set(doc);
            maxDocId = doc;
         } else {
            // expand existing writer as default
            OffsetGrowableWriter next = internalWriter;
            int internalWriterSize = internalWriter.size();

            // grow internal representation if needed
            if (docs.cardinality() == internalWriterSize) {
               next = new OffsetGrowableWriter(
                     internalWriter.getBitsPerValue(),
                     internalWriterSize + intialSize,
                     acceptableOverheadRatio,
                     internalWriter.getCurrentMinimumValue());

               // copy existing values
               PackedInts.copy(internalWriter.getMutable(), 0, next.getMutable(), 0, internalWriterSize,
                     DEFAULT_BUFFER_SIZE);

               // update current size
               internalWriterSize += intialSize;
            }

            // append
            if (doc > maxDocId) {
               // append given one
               next.set(++maxDocId, value);
            } else {

               // insert: move trailing values towards the end
               int index = internalIndexOf(doc);
               PackedInts.copy(next.getMutable(), index,
                     next.getMutable(), index + 1, internalWriterSize - index - 1,
                     DEFAULT_BUFFER_SIZE);

               // insert new value
               next.set(index, value);
            }

            // mark doc
            docs.set(doc);

            // replace internal representation
            this.internalWriter = next;
         }
      } catch (Exception e) {
         throw new RuntimeException(e);
      }
   }

   protected int internalIndexOf(int doc) {
      checkArgument(doc >= 0, "Pre-condition violated: expression doc >= 0 must be true.");

      int index = 0;
      int currentDoc = 0;

      // this is the long representation. For a -1, advance 64 indexes / docs,
      // for a 0, advance 64 documents
      long[] internals = docs.getBits();
      final int bitsPerLong = Long.bitCount(-1);

      // advance docs/index until either eo docs or eo words
      // is reached.
      for (int i = 0; i < internals.length && (currentDoc + bitsPerLong) < doc; i++) {
         currentDoc += bitsPerLong;
         index += Long.bitCount(internals[i]);
      }

      do {
         if (doc != currentDoc && docs.get(currentDoc)) {
            index++;
         }

         currentDoc++;
      } while (currentDoc <= doc);

      return index;
   }

   @Override
   public long get(int doc) {
      if (docs.get(doc)) {
         // use index to retrieve value
         return internalWriter.get(internalIndexOf(doc));
      }

      return defaultValue;
   }

   // delegations

   @Override
   public long ramBytesUsed() {
      return internalWriter.ramBytesUsed() + docs.ramBytesUsed() + 8;
   }

   @Override
   public int getBitsPerValue() {
      return internalWriter.getBitsPerValue();
   }

   @Override
   public int size() {
      return internalWriter.size();
   }

}
