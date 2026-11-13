"""Reliability-relevant Celery settings. Changing any of these changes recovery behaviour."""

from khmerdub_ai.celery_app import app


def test_tasks_are_acknowledged_only_after_they_finish():
    assert app.conf.task_acks_late is True
    assert app.conf.task_reject_on_worker_lost is True  # a crashed task process puts the message back
    assert app.conf.worker_prefetch_multiplier == 1     # never hold queued work hostage in a busy worker


def test_redis_connection_loss_does_not_kill_running_work():
    # Scenario S6: with True, a Redis restart terminated the running segment and it had to run again.
    assert app.conf.worker_cancel_long_running_tasks_on_connection_loss is False


def test_engine_task_is_registered_and_fire_and_forget():
    import khmerdub_ai.tasks.attempts  # noqa: F401 - registers the task

    task = app.tasks["khmerdub.attempt.run"]
    assert task.ignore_result is True
