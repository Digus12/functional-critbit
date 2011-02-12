package io.prelink.critbit;

import org.ardverk.collection.KeyAnalyzer;

/**
 * Like immutable.CritBitTree, except w/ nodes that are mutable where it makes
 * sense, hypothesis being we can cut down on garbage collection a bit.
 */
public final class MCritBitTree<K,V> extends AbstractCritBitTree<K,V> {

    static final class MShortLeftNode<K,V> extends AbstractInternal<K,V> {
        private final K leftKey;
        private V leftVal;
        private Node<K,V> right;
        public MShortLeftNode(int bit, K leftKey, V leftVal, Node<K,V> right) {
            super(bit);
            this.leftKey = leftKey;
            this.leftVal = leftVal;
            this.right = right;
        }
        public Node<K,V> left(Context<K,V> ctx) { return ctx.nf.mkLeaf(leftKey, leftVal); }
        public Node<K,V> right(Context<K,V> ctx) { return right; }
        public Node<K,V> setLeft(int diffBit, K key, V val, Context<K,V> ctx) {
            if(diffBit < 0) {
                this.leftVal = val;
                return this;
            }
            Node<K,V> newLeft = mkShortBothChild(diffBit, key, val, leftKey, leftVal, ctx);
            return ctx.nf.mkTall(bit(), newLeft, right);
        }
        public Node<K,V> setRight(int diffBit, K key, V val, Context<K,V> ctx) {
            this.right = right.insert(diffBit, key, val, ctx);
            return this;
        }
        public boolean hasExternalLeft() { return true; }
        public boolean hasExternalRight() { return false; }
    }

    static final class MShortRightNode<K,V> extends AbstractInternal<K,V> {
        private Node<K,V> left;
        private final K rightKey;
        private V rightVal;
        public MShortRightNode(int bit, Node<K,V> left, K rightKey, V rightVal) {
            super(bit);
            this.left = left;
            this.rightKey = rightKey;
            this.rightVal = rightVal;
        }
        public Node<K,V> left(Context<K,V> ctx) { return left; }
        public Node<K,V> right(Context<K,V> ctx) { return ctx.nf.mkLeaf(rightKey, rightVal); }
        public Node<K,V> setLeft(int diffBit, K key, V val, Context<K,V> ctx) {
            this.left = left.insert(diffBit, key, val, ctx);
            return this;
        }
        public Node<K,V> setRight(int diffBit, K key, V val, Context<K,V> ctx) {
            if(diffBit < 0) {
                this.rightVal = val;
                return this;
            }
            Node<K,V> newRight = mkShortBothChild(diffBit, key, val, rightKey, rightVal, ctx);
            return ctx.nf.mkTall(bit(), left, newRight);
        }
        public boolean hasExternalLeft() { return false; }
        public boolean hasExternalRight() { return true; }
    }

    static final class MTallNode<K,V> extends AbstractInternal<K,V> {
        private Node<K,V> left;
        private Node<K,V> right;
        public MTallNode(int bit, Node<K,V> left, Node<K,V> right) {
            super(bit);
            this.left = left;
            this.right = right;
        }
        public Node<K,V> left(Context<K,V> ctx) { return left; }
        public Node<K,V> right(Context<K,V> ctx) { return right; }
        public Node<K,V> setLeft(int diffBit, K key, V val, Context<K,V> ctx) {
            this.left = left.insert(diffBit, key, val, ctx);
            return this;
        }
        public Node<K,V> setRight(int diffBit, K key, V val, Context<K,V> ctx) {
            this.right = right.insert(diffBit, key, val, ctx);
            return this;
        }
        public boolean hasExternalLeft() { return false; }
        public boolean hasExternalRight() { return false; }
    }

    static final class MutableNodeFactory<K,V> implements NodeFactory<K,V> {
        public Node<K,V> mkShortBoth(int diffBit, K lk, V lv, K rk, V rv) {
            return new ShortBothNode<K,V>(diffBit, lk, lv, rk, rv);
        }
        public Node<K,V> mkShortRight(int diffBit, Node<K,V> left, K k, V v) {
            return new MShortRightNode<K,V>(diffBit, left, k, v);
        }
        public Node<K,V> mkShortLeft(int diffBit, K k, V v, Node<K,V> right) {
            return new MShortLeftNode<K,V>(diffBit, k, v, right);
        }
        public Node<K,V> mkTall(int diffBit, Node<K,V> left, Node<K,V> right) {
            return new MTallNode<K,V>(diffBit, left, right);
        }
        public Node<K,V> mkLeaf(K key, V val) {
            return new LeafNode<K,V>(key, val);
        }
    }

    private Node<K,V> root;
    private int size;

    public MCritBitTree(KeyAnalyzer<K> analyzer) {
        this(null,
             new Context<K,V>(analyzer, new MutableNodeFactory<K,V>()));
    }

    private MCritBitTree(Node<K,V> root, Context<K,V> ctx) {
        super(ctx);
        this.root = root;
    }

    Node<K,V> root() { return root; }

    public V put(K key, V val) {
        if(root == null) {
            root = ctx().nf.mkLeaf(key, val);
            size++;
            return null;
        }
        if(!root.isInternal()) {
            int diffBit = ctx().chk.bitIndex(key, root.key());
            V oldVal = root.value();
            root = root.insert(diffBit, key, val, ctx());
            if(diffBit >= 0) {
                size++;
                return null;
            } else {
                return oldVal;
            }
        }

        final SearchResult<K,V> sr = search(root, key);
        final int diffBit = ctx().chk.bitIndex(key, sr.key(ctx()));
        V out = null;
        if(diffBit >= 0) {
            out = sr.value(ctx());
            size++;
        }

        if(sr.parent == null) {
            root = root.insert(diffBit, key, val, ctx());
            return out;
        } else if(diffBit < 0 || diffBit >= sr.parent.bit()) {
            switch(sr.pDirection) {
            case LEFT:
                sr.parent.setLeft(diffBit, key, val, ctx());
                return out;
            case RIGHT:
                sr.parent.setRight(diffBit, key, val, ctx());
                return out;
            }
        }

        Node<K,V> prev = root;
        Node<K,V> current = prev.nextNode(key, ctx());
        for(;;) {
            if(diffBit < current.bit()) {
                if(ctx().chk.isBitSet(key, prev.bit())) {
                    prev.setRight(diffBit, key, val, ctx());
                } else {
                    prev.setLeft(diffBit, key, val, ctx());
                }
                return out;
            } else {
                prev = current;
                current = current.nextNode(key, ctx());
            }
        }
    }

    public int size() {
        return size;
    }

}