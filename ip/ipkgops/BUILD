load("@io_bazel_rules_scala//scala:scala.bzl", "scala_library", "scala_binary", "scala_test")

scala_library(
    name = "ipkgops",
    srcs = glob(["*.scala"]),
    deps = [
      "//ip/describe:describe",
      "//ip/fileops/path:path",
      "//ip/result:result",
      "//ip/stringext:stringext"],
    visibility = ["//visibility:public"]
)


