"""Request-local transaction checks, propagated by Starlette into its worker thread."""
from contextvars import ContextVar

transaction_authorization = ContextVar("admission_transaction_authorization", default=None)


def revalidate(connection):
    check = transaction_authorization.get()
    if check is not None:
        check(connection)
