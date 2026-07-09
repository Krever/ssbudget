package ssbudget.shared.api

import io.circe.Codec
import ssbudget.shared.model.*

/** A bank (ASPSP) that can be connected via Enable Banking. */
final case class Aspsp(name: String, country: String) derives Codec.AsObject

final case class ConnectBankRequest(aspspName: String, aspspCountry: String) derives Codec.AsObject

/** URL the browser must be redirected to so the user can authorize at their bank. */
final case class ConnectBankResponse(redirectUrl: String) derives Codec.AsObject

final case class BankCallbackRequest(code: String, state: String) derives Codec.AsObject

/** A connection plus its authorized bank accounts. */
final case class BankConnectionView(
    connection: BankConnection,
    accounts: List[BankAccountLink],
) derives Codec.AsObject

/** Point a bank account link at its [[BankLinkTarget]] (or `Unlinked` to detach). */
final case class LinkAccountRequest(target: BankLinkTarget) derives Codec.AsObject

/** Link a card group's remaining-limit mirror to an app account (`None` to unlink). */
final case class LinkCardGroupRequest(accountId: Option[AccountId]) derives Codec.AsObject

/** Create a shared-limit credit-card group (initially unlinked; link it to an app account afterwards to feed the budget). */
final case class CreateCardGroup(name: String, limitCents: Long, currency: Currency) derives Codec.AsObject
