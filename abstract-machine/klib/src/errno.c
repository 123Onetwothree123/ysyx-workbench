#include <am.h>
#include <errno.h>

#if !defined(__ISA_NATIVE__) || defined(__NATIVE_USE_KLIB__)
int errno;

static const char *errno_description(int errnum) {
  switch (errnum) {
    case 0: return "Success";
    case EPERM: return "Operation not permitted";
    case ENOENT: return "No such file or directory";
    case ESRCH: return "No such process";
    case EINTR: return "Interrupted system call";
    case EIO: return "Input/output error";
    case ENXIO: return "No such device or address";
    case E2BIG: return "Argument list too long";
    case ENOEXEC: return "Exec format error";
    case EBADF: return "Bad file descriptor";
    case ECHILD: return "No child processes";
    case EAGAIN: return "Resource temporarily unavailable";
    case ENOMEM: return "Cannot allocate memory";
    case EACCES: return "Permission denied";
    case EFAULT: return "Bad address";
    case ENOTBLK: return "Block device required";
    case EBUSY: return "Device or resource busy";
    case EEXIST: return "File exists";
    case EXDEV: return "Invalid cross-device link";
    case ENODEV: return "No such device";
    case ENOTDIR: return "Not a directory";
    case EISDIR: return "Is a directory";
    case EINVAL: return "Invalid argument";
    case ENFILE: return "Too many open files in system";
    case EMFILE: return "Too many open files";
    case ENOTTY: return "Inappropriate ioctl for device";
    case ETXTBSY: return "Text file busy";
    case EFBIG: return "File too large";
    case ENOSPC: return "No space left on device";
    case ESPIPE: return "Illegal seek";
    case EROFS: return "Read-only file system";
    case EMLINK: return "Too many links";
    case EPIPE: return "Broken pipe";
    case EDOM: return "Numerical argument out of domain";
    case ERANGE: return "Numerical result out of range";
    case EDEADLK: return "Resource deadlock avoided";
    case ENAMETOOLONG: return "File name too long";
    case ENOLCK: return "No locks available";
    case ENOSYS: return "Function not implemented";
    case ENOTEMPTY: return "Directory not empty";
    case ELOOP: return "Too many levels of symbolic links";
    case ENOMSG: return "No message of desired type";
    case EIDRM: return "Identifier removed";
    case ENOSTR: return "Device not a stream";
    case ENODATA: return "No data available";
    case ETIME: return "Timer expired";
    case ENOSR: return "Out of streams resources";
    case ENOLINK: return "Link has been severed";
    case EPROTO: return "Protocol error";
    case EMULTIHOP: return "Multihop attempted";
    case EBADMSG: return "Bad message";
    case EOVERFLOW: return "Value too large for defined data type";
    case EILSEQ: return "Invalid or incomplete multibyte or wide character";
    case ENOTSOCK: return "Socket operation on non-socket";
    case EDESTADDRREQ: return "Destination address required";
    case EMSGSIZE: return "Message too long";
    case EPROTOTYPE: return "Protocol wrong type for socket";
    case ENOPROTOOPT: return "Protocol not available";
    case EPROTONOSUPPORT: return "Protocol not supported";
    case ESOCKTNOSUPPORT: return "Socket type not supported";
    case EOPNOTSUPP: return "Operation not supported";
    case EPFNOSUPPORT: return "Protocol family not supported";
    case EAFNOSUPPORT: return "Address family not supported by protocol";
    case EADDRINUSE: return "Address already in use";
    case EADDRNOTAVAIL: return "Cannot assign requested address";
    case ENETDOWN: return "Network is down";
    case ENETUNREACH: return "Network is unreachable";
    case ENETRESET: return "Network dropped connection on reset";
    case ECONNABORTED: return "Software caused connection abort";
    case ECONNRESET: return "Connection reset by peer";
    case ENOBUFS: return "No buffer space available";
    case EISCONN: return "Transport endpoint is already connected";
    case ENOTCONN: return "Transport endpoint is not connected";
    case ESHUTDOWN: return "Cannot send after transport endpoint shutdown";
    case ETOOMANYREFS: return "Too many references: cannot splice";
    case ETIMEDOUT: return "Connection timed out";
    case ECONNREFUSED: return "Connection refused";
    case EHOSTDOWN: return "Host is down";
    case EHOSTUNREACH: return "No route to host";
    case EALREADY: return "Operation already in progress";
    case EINPROGRESS: return "Operation now in progress";
    case ESTALE: return "Stale file handle";
    case EDQUOT: return "Disk quota exceeded";
    case ECANCELED: return "Operation canceled";
    case EOWNERDEAD: return "Owner died";
    case ENOTRECOVERABLE: return "State not recoverable";
    default: return NULL;
  }
}

static size_t copy_message(char *buffer, size_t buffer_size,
                           const char *message) {
  size_t length = 0;

  while (message[length] != '\0') {
    if (buffer != NULL && length + 1 < buffer_size) {
      buffer[length] = message[length];
    }
    length++;
  }

  if (buffer != NULL && buffer_size != 0) {
    size_t terminator = length < buffer_size ? length : buffer_size - 1;
    buffer[terminator] = '\0';
  }
  return length;
}

static size_t format_unknown_error(int errnum, char *buffer,
                                   size_t buffer_size) {
  static const char prefix[] = "Unknown error ";
  char digits[sizeof(unsigned int) * 3];
  unsigned int magnitude = (unsigned int)errnum;
  size_t length = 0;
  size_t prefix_index = 0;
  size_t digit_count = 0;

  if (errnum < 0) {
    magnitude = 0u - magnitude;
  }

  while (prefix[prefix_index] != '\0') {
    if (buffer != NULL && length + 1 < buffer_size) {
      buffer[length] = prefix[prefix_index];
    }
    length++;
    prefix_index++;
  }

  if (errnum < 0) {
    if (buffer != NULL && length + 1 < buffer_size) {
      buffer[length] = '-';
    }
    length++;
  }

  do {
    digits[digit_count++] = (char)('0' + magnitude % 10u);
    magnitude /= 10u;
  } while (magnitude != 0u);

  while (digit_count != 0) {
    digit_count--;
    if (buffer != NULL && length + 1 < buffer_size) {
      buffer[length] = digits[digit_count];
    }
    length++;
  }

  if (buffer != NULL && buffer_size != 0) {
    size_t terminator = length < buffer_size ? length : buffer_size - 1;
    buffer[terminator] = '\0';
  }
  return length;
}

char *strerror(int errnum) {
  static char unknown_error[sizeof(int) * 3 + sizeof("Unknown error -")];
  const char *description = errno_description(errnum);

  if (description != NULL) {
    return (char *)description;
  }

  format_unknown_error(errnum, unknown_error, sizeof(unknown_error));
  return unknown_error;
}

int strerror_r(int errnum, char *buffer, size_t buffer_size) {
  const char *description;
  size_t length;
  int result = 0;

  if (buffer == NULL) {
    return EINVAL;
  }

  description = errno_description(errnum);
  if (description != NULL) {
    length = copy_message(buffer, buffer_size, description);
  } else {
    length = format_unknown_error(errnum, buffer, buffer_size);
    result = EINVAL;
  }

  if (length >= buffer_size) {
    return ERANGE;
  }
  return result;
}
#endif
