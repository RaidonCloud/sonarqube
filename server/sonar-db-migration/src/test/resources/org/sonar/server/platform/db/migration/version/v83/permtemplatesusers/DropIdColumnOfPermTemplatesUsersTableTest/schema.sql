CREATE TABLE "PERM_TEMPLATES_USERS"(
    "ID" INTEGER NOT NULL,
    "UUID" VARCHAR(40) NOT NULL,
    "USER_ID" INTEGER NOT NULL,
    "TEMPLATE_ID" INTEGER NOT NULL,
    "PERMISSION_REFERENCE" VARCHAR(64) NOT NULL,
    "CREATED_AT" TIMESTAMP,
    "UPDATED_AT" TIMESTAMP
);
ALTER TABLE "PERM_TEMPLATES_USERS" ADD CONSTRAINT "PK_PERM_TEMPLATES_USERS" PRIMARY KEY("UUID");
