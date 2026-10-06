package io.github.mgeladzerezo.miniorm.examples;

import io.github.mgeladzerezo.miniorm.annotation.Column;
import io.github.mgeladzerezo.miniorm.annotation.CreatedAt;
import io.github.mgeladzerezo.miniorm.annotation.Entity;
import io.github.mgeladzerezo.miniorm.annotation.GeneratedValue;
import io.github.mgeladzerezo.miniorm.annotation.Id;
import io.github.mgeladzerezo.miniorm.annotation.ManyToOne;
import io.github.mgeladzerezo.miniorm.annotation.OneToMany;
import io.github.mgeladzerezo.miniorm.annotation.Version;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** The blog domain of the example application: authors write posts, readers comment on them. */
public final class Domain {

    private Domain() {
    }

    /** A blog author. */
    @Entity
    public static class Author {
        @Id
        @GeneratedValue
        private Long id;

        @Column(length = 80, nullable = false)
        private String name;

        @OneToMany(mappedBy = "author")
        private List<Post> posts = new ArrayList<>();

        protected Author() {
        }

        public Author(String name) {
            this.name = name;
        }

        public Long getId() {
            return id;
        }

        public String getName() {
            return name;
        }

        public List<Post> getPosts() {
            return posts;
        }
    }

    /** A post; versioned so that two editors cannot silently overwrite each other. */
    @Entity
    public static class Post {
        @Id
        @GeneratedValue
        private Long id;

        @ManyToOne
        private Author author;

        @Column(length = 200, nullable = false)
        private String title;

        @Column(length = 0)
        private String body;

        @Version
        private long version;

        @CreatedAt
        private Instant createdAt;

        @OneToMany(mappedBy = "post")
        private List<Comment> comments = new ArrayList<>();

        protected Post() {
        }

        public Post(Author author, String title, String body) {
            this.author = author;
            this.title = title;
            this.body = body;
        }

        public Long getId() {
            return id;
        }

        public Author getAuthor() {
            return author;
        }

        public String getTitle() {
            return title;
        }

        public void setTitle(String title) {
            this.title = title;
        }

        public String getBody() {
            return body;
        }

        public Instant getCreatedAt() {
            return createdAt;
        }

        public long getVersion() {
            return version;
        }

        public List<Comment> getComments() {
            return comments;
        }
    }

    /** A comment on a post. */
    @Entity
    public static class Comment {
        @Id
        @GeneratedValue
        private Long id;

        @ManyToOne
        private Post post;

        @Column(length = 500)
        private String text;

        protected Comment() {
        }

        public Comment(Post post, String text) {
            this.post = post;
            this.text = text;
        }

        public Long getId() {
            return id;
        }

        public Post getPost() {
            return post;
        }

        public String getText() {
            return text;
        }
    }
}
